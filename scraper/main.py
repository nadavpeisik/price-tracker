import contextvars
import logging
import time
import uuid
from contextlib import asynccontextmanager
from enum import Enum

from fastapi import FastAPI, HTTPException, Request
from playwright.async_api import Browser, async_playwright
from pydantic import BaseModel, field_validator

browser: Browser | None = None

_correlation_id: contextvars.ContextVar[str] = contextvars.ContextVar("correlation_id", default="-")

_original_log_record_factory = logging.getLogRecordFactory()


def _log_record_factory(*args, **kwargs):
    record = _original_log_record_factory(*args, **kwargs)
    record.correlation_id = _correlation_id.get()
    return record


logging.setLogRecordFactory(_log_record_factory)

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(correlation_id)s] %(levelname)s %(name)s - %(message)s",
)

# JavaScript run via page.evaluate() to strip noise from the DOM before extraction
# `<style>` is intentionally NOT pruned: stylesheets contain the display:none rules
# that hide out-of-stock templates kept in the DOM. Removing them would make
# is_visible() report every hidden template as visible.
_DOM_PRUNE_SCRIPT = """() => {
    const selectors = [
        'nav', 'footer', 'script', 'noscript',
        '[class*="cookie"]', '[class*="banner"]', '[class*="ad-"]',
        '[id*="cookie"]', '[id*="popup"]'
    ];
    selectors.forEach(s => document.querySelectorAll(s).forEach(el => el.remove()));
}"""

# Chrome hider: injects a <style> that display:none's the _DOM_PRUNE_SCRIPT selectors MINUS
# <script>/<noscript>, run before _wait_for_render. innerText (and checkVisibility) honor computed
# style, so hidden chrome leaves the render-settle measurement — the gate tracks product content,
# not nav/footer/cookie/promo chrome that renders early and would false-settle it.
#
# A *stylesheet rule* rather than removing nodes or setting inline styles, because:
#   - non-destructive: nodes stay in the DOM, so removing-mid-hydration crashes (framework refs /
#     querySelector null-derefs) can't happen;
#   - reactive: the rule applies to *any* matching node, so SPA re-renders that tear down and recreate
#     nav/banner/footer get hidden too — inline styles would be lost on the new nodes and let chrome
#     back into innerText, re-settling the gate on chrome.
# <script> stays intact for Tier-1 JSON-LD; the full _DOM_PRUNE_SCRIPT removes the hidden nodes
# post-Tier-1. document.head may not exist this early, so fall back to documentElement.
_HIDE_CHROME_SCRIPT = """() => {
    const styleId = 'scraper-hide-chrome';
    if (document.getElementById(styleId)) return;
    const selectors = [
        'nav', 'footer',
        '[class*="cookie"]', '[class*="banner"]', '[class*="ad-"]',
        '[id*="cookie"]', '[id*="popup"]'
    ];
    const style = document.createElement('style');
    style.id = styleId;
    style.textContent = selectors.map(s => s + ' { display: none !important; }').join('\\n');
    (document.head || document.documentElement).appendChild(style);
}"""

# JavaScript run via page.evaluate() to extract structured price data. Tries
# JSON-LD first (Schema.org as embedded script); if that yields nothing, falls
# back to Schema.org Microdata (itemprop/itemtype attributes on the HTML itself).
_STRUCTURED_DATA_SCRIPT = """() => {
    // MSRP-style labels we want to discard when both list and sale prices are
    // present. Match by suffix so 'https://schema.org/ListPrice' and bare
    // 'ListPrice' both work.
    const MSRP_SUFFIXES = ['ListPrice', 'MSRP', 'SRP', 'RegularPrice', 'StrikethroughPrice'];
    const isMsrpType = (pt) => {
        if (!pt) return false;
        const s = String(pt);
        return MSRP_SUFFIXES.some(suf => s === suf || s.endsWith('/' + suf));
    };

    // Schema.org types that represent the product's unit price. Anything else
    // in priceSpecification[] (DeliveryChargeSpecification, PaymentChargeSpec,
    // etc.) carries shipping/tax/fees, not the item price — must be filtered
    // out before the min-price reduce, or we'd return shipping as the product.
    // Untyped entries pass through (publishers commonly omit @type on the
    // canonical UnitPriceSpecification).
    const PRODUCT_PRICE_TYPES = ['UnitPriceSpecification', 'PriceSpecification', 'CompoundPriceSpecification'];
    const isProductPriceType = (atType) => {
        if (!atType) return true;
        const s = String(atType);
        return PRODUCT_PRICE_TYPES.some(t => s === t || s.endsWith('/' + t));
    };

    const parseNumeric = (raw) => {
        if (raw === undefined || raw === null) return NaN;
        const cleaned = String(raw).replace(/[^0-9.,]/g, '');
        const lastDot = cleaned.lastIndexOf('.');
        const lastComma = cleaned.lastIndexOf(',');
        const sep = lastDot > lastComma ? '.' : (lastComma > lastDot ? ',' : null);
        if (sep === null) {
            return parseFloat(cleaned);
        }
        const sepIdx = cleaned.lastIndexOf(sep);
        const tail = cleaned.substring(sepIdx + 1);
        // Treat as thousands-grouping (strip all separators) when either:
        //  - the separator appears more than once ("1.234.567" — every dot is grouping)
        //  - it appears once with exactly 3 trailing digits ("1,234", "9,990" —
        //    ambiguous in isolation, but in practice almost always a thousands
        //    separator; a decimal with exactly 3 trailing digits is rare outside
        //    of scientific notation, while comma-thousands is common, especially
        //    on ILS/EUR sites that emit prices like "9,990" in JSON-LD).
        if (cleaned.split(sep).length > 2 || tail.length === 3) {
            return parseFloat(cleaned.replace(/[^0-9]/g, ''));
        }
        return parseFloat(
            cleaned.substring(0, sepIdx).replace(/[^0-9]/g, '')
            + '.'
            + tail
        );
    };

    // "can you get it" — orderable states (presale/preorder/backorder/onlineonly) count as available.
    const AVAILABLE_TOKENS = ['instock', 'instoreonly', 'onlineonly', 'limitedavailability', 'presale', 'preorder', 'backorder'];
    const UNAVAILABLE_TOKENS = ['outofstock', 'soldout', 'discontinued'];
    // Normalize a schema.org availability value (full URI, bare token, or {@id}/{url} object) to a
    // bare lowercase alphanumeric token: take the leaf segment (drops the schema.org/ prefix), strip
    // any ?query/#fragment/trailing-slash, then non-alphanumerics ('In Stock'/'in-stock' -> 'instock').
    const normalizeAvailability = (availability) => {
        let raw = availability;
        if (raw && typeof raw === 'object') raw = raw['@id'] || raw.url || '';
        let s = String(raw || '').trim();
        if (!s) return '';
        s = s.split('?')[0].split('#')[0].replace(/\\/+$/, '');
        const leaf = s.substring(s.lastIndexOf('/') + 1);
        return leaf.toLowerCase().replace(/[^a-z0-9]/g, '');
    };
    const buildResult = (price, currency, availability) => {
        if (isNaN(price) || price <= 0 || !currency) return null;
        const token = normalizeAvailability(availability);
        let status = 'unknown';
        if (AVAILABLE_TOKENS.includes(token)) status = 'available';
        else if (UNAVAILABLE_TOKENS.includes(token)) status = 'unavailable';
        return {
            price: price,
            currency: currency,
            availability: status
        };
    };

    // Resolve the active price for a JSON-LD Offer. Sites with sales sometimes
    // omit offer.price entirely and put both prices in priceSpecification[] as
    // UnitPriceSpecification entries (one labelled ListPrice for MSRP, one
    // unlabelled for the sale). Filter MSRP-tagged entries out, then pick the
    // lowest of the survivors. Fall back to overall-lowest if every entry is
    // tagged ListPrice (broken publisher) so we still return *a* price.
    const resolveOfferPrice = (offer) => {
        let rawPrice = offer.price ?? offer.lowPrice;
        let currency = offer.priceCurrency;
        if (rawPrice !== undefined && rawPrice !== null) {
            return { rawPrice, currency };
        }
        const raw = offer.priceSpecification;
        const specs = Array.isArray(raw) ? raw : (raw ? [raw] : []);
        const valid = specs
            .map(s => ({ spec: s, num: parseNumeric(s && s.price) }))
            .filter(x => !isNaN(x.num) && x.num > 0 && isProductPriceType(x.spec && x.spec['@type']));
        if (valid.length === 0) return { rawPrice: undefined, currency };
        const survivors = valid.filter(x => !isMsrpType(x.spec.priceType));
        const pool = survivors.length > 0 ? survivors : valid;
        const chosen = pool.reduce((min, x) => x.num < min.num ? x : min);
        return {
            rawPrice: chosen.spec.price,
            currency: currency || chosen.spec.priceCurrency,
        };
    };

    // Tier 1a: JSON-LD
    const scripts = Array.from(document.querySelectorAll('script[type="application/ld+json"]'));
    for (const script of scripts) {
        try {
            const data = JSON.parse(script.textContent);
            const nodes = Array.isArray(data) ? data : [data];
            for (const node of nodes) {
                const items = node['@graph'] ? node['@graph'] : [node];
                for (const item of items) {
                    const type = item['@type'];
                    let offer = null;
                    if (type === 'Product' || type === 'IndividualProduct') {
                        offer = item.offers || item.offer;
                        if (Array.isArray(offer)) offer = offer[0];
                    } else if (type === 'Offer' || type === 'AggregateOffer') {
                        offer = item;
                    }
                    if (!offer) continue;
                    const { rawPrice, currency } = resolveOfferPrice(offer);
                    const result = buildResult(parseNumeric(rawPrice), currency, offer.availability);
                    if (result) return result;
                }
            }
        } catch (e) {}
    }

    // Tier 1b: Schema.org Microdata fallback. Suffix match on itemtype covers
    // 'https://schema.org/Offer' and the legacy http variant.
    const readProp = (root, name) => {
        const el = root.querySelector('[itemprop="' + name + '"]');
        if (!el) return null;
        const v = el.getAttribute('content')
            || el.getAttribute('href')
            || (el.innerText || '').trim();
        return v || null;
    };
    const offerEls = document.querySelectorAll(
        '[itemtype$="/Offer"], [itemtype$="/AggregateOffer"]'
    );
    for (const offerEl of offerEls) {
        const rawPrice = readProp(offerEl, 'price') ?? readProp(offerEl, 'lowPrice');
        const currency = readProp(offerEl, 'priceCurrency');
        const availability = readProp(offerEl, 'availability') || '';
        const result = buildResult(parseNumeric(rawPrice), currency, availability);
        if (result) return result;
    }

    return null;
}"""

# JavaScript run via page.evaluate() to detect the STORE/site name (issue #33), tiered by
# confidence. og:site_name and Schema.org Organization/WebSite/Store are site-level signals
# ("strong" — safe to persist as the domain's name); the <title> heuristic is a "weak" last
# resort. A marketplace seller (offers.seller.name) is deliberately NOT read here: it names the
# third-party seller of one listing, not the storefront, and would poison the shared domain
# mapping — capturing it belongs in a separate per-listing field (follow-up issue). JSON-LD
# `brand` is never read either — it's the manufacturer ("Sony"), not the shop. Returns
# {name, strong} or null.
_SITE_NAME_SCRIPT = """() => {
    // Strip Unicode bidi/directional control chars (RTL pages wrap titles in them) so a leading
    // mark can't glue to a segment and defeat the og:title de-dup below; then trim.
    const clean = (s) => {
        if (typeof s !== 'string') return '';
        let out = '';
        for (const ch of s) {
            const c = ch.codePointAt(0);
            if ((c >= 0x200e && c <= 0x200f) || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069)) continue;
            out += ch;
        }
        return out.trim();
    };

    // Tier A: OpenGraph site name — strong.
    const og = document.querySelector('meta[property="og:site_name"]');
    const ogName = og ? clean(og.getAttribute('content')) : '';
    if (ogName) return { name: ogName, strong: true };

    // Collect JSON-LD nodes at the top level only (each block's top-level entries + one level of
    // @graph) — deliberately NOT recursing into nested objects, so a nested offers.seller
    // Organization can't be mistaken for the storefront. Each block parsed in its own try/catch.
    const nodes = [];
    const addTop = (data) => {
        const arr = Array.isArray(data) ? data : [data];
        for (const n of arr) {
            if (!n || typeof n !== 'object') continue;
            nodes.push(n);
            if (Array.isArray(n['@graph'])) {
                for (const g of n['@graph']) if (g && typeof g === 'object') nodes.push(g);
            }
        }
    };
    for (const s of document.querySelectorAll('script[type="application/ld+json"]')) {
        try { addTop(JSON.parse(s.textContent)); } catch (e) {}
    }
    const typesOf = (n) => {
        const t = n && n['@type'];
        if (!t) return [];
        return (Array.isArray(t) ? t : [t]).map(x => String(x).toLowerCase());
    };
    const ORG_TYPES = ['organization', 'website', 'store', 'onlinestore', 'corporation', 'localbusiness'];
    const stripWww = (h) => (h.startsWith('www.') ? h.slice(4) : h);
    const pageHost = stripWww((location.hostname || '').toLowerCase());
    const hostOf = (v) => {
        if (typeof v !== 'string') return '';
        const t = v.trim();
        const s = t.startsWith('//') ? 'https:' + t : t; // resolve protocol-relative urls
        if (!(s.startsWith('http://') || s.startsWith('https://'))) return ''; // ignore relative / #frag @id
        try { return stripWww(new URL(s).hostname.toLowerCase()); } catch (e) { return ''; }
    };
    const sameSite = (h) => h === pageHost || h.endsWith('.' + pageHost) || pageHost.endsWith('.' + h);

    // Tier B: Schema.org Organization/WebSite/Store name. An org whose url/@id host MATCHES the page
    // is the site publisher → strong (safe to learn into the shared domain mapping). An org on a
    // DIFFERENT host is a brand/manufacturer (e.g. url=sony.com on a shop page) → skipped, so it can
    // never poison the domain mapping. An org with no resolvable host is shown but NOT learned (weak).
    let weakOrgName = null;
    for (const n of nodes) {
        if (!(typesOf(n).some(t => ORG_TYPES.includes(t)) && clean(n.name))) continue;
        const orgHost = hostOf(n.url) || hostOf(n['@id']);
        if (orgHost && sameSite(orgHost)) {
            return { name: clean(n.name), strong: true };
        }
        if (!orgHost && weakOrgName === null) {
            weakOrgName = clean(n.name);
        }
    }
    if (weakOrgName !== null) {
        return { name: weakOrgName, strong: false };
    }

    // Tier C: <title> heuristic — weak. A title with no separator is just the product name (no
    // shop segment), so it is rejected outright (return null) rather than used verbatim — the
    // product name must never be mistaken for the shop name. Only a title with a real separator is
    // mined for a shop segment: split on pipe / en- / em-dash / spaced ascii hyphen (so "SLO-30" /
    // "Wi-Fi" don't split), else fall back to colon. Drop the product segment (anything contained
    // in og:title) and the bare-host segment; accept only if exactly one segment survives.
    const title = clean(document.title);
    if (title) {
        let parts = title.split(/\\s+[|\\u2013\\u2014-]\\s+/).map(clean).filter(Boolean);
        if (parts.length < 2) parts = title.split(/\\s*:\\s*/).map(clean).filter(Boolean);
        if (parts.length >= 2) {
            const ot = document.querySelector('meta[property="og:title"]');
            const ogTitle = (ot ? clean(ot.getAttribute('content')) : '').toLowerCase();
            const host = (location.hostname || '').replace(/^www\\./, '').toLowerCase();
            const survivors = parts.filter(p => {
                const pl = p.toLowerCase();
                if (ogTitle && ogTitle.includes(pl)) return false;
                if (host && pl === host) return false;
                return true;
            });
            if (survivors.length === 1) return { name: survivors[0], strong: false };
        }
    }

    return null;
}"""

# JavaScript run via page.evaluate() to remove decoy prices (strikethrough MSRP
# and paired .regular-price) from the rendered DOM. Runs before Tier 1 so
# microdata's innerText-based reads see clean values; runs before Tier 2 so the
# snippet doesn't flatten both prices into one string. Safe to run before
# _DOM_PRUNE_SCRIPT because it does not touch <script> tags — JSON-LD survives.
_STRIP_DECOY_PRICES_SCRIPT = """() => {
    // Digit-gated: <del>/<s>/<strike> wrapping non-numeric text (e.g.
    // <s>Sold Out</s>) is a UX signal Tier 2/3 needs for availability.
    // Only strip when the node contains numerals — that's the price-MSRP case.
    // Class selector uses [class~=] (exact whitespace-separated token) instead
    // of [class*=] so we don't accidentally strip a `.not-strikethrough` opt-out
    // class. <del>/<s>/<strike> tags catch all the semantic cases anyway.
    document.querySelectorAll('del, s, strike, [class~="strikethrough"], [class~="strikethrough-price"]')
        .forEach(n => { if (/[0-9]/.test(n.textContent || '')) n.remove(); });

    // Conditional: .regular-price means "MSRP" only when paired with a
    // .sale-price sibling. Walk up from each sale-price until we find an
    // ancestor that actually contains a regular-price. Depth cap +
    // class-based firewall guard against cross-card poisoning on PDPs with
    // related-product carousels.
    const MAX_ASCENT = 4;
    const MACRO_WORDS = ['grid', 'row', 'carousel', 'list', 'table'];
    // Legacy table-based product grids: <tr>/<table> often carry no class, so
    // a className-only firewall would miss them. Treat their tags as a hard
    // boundary regardless of class.
    const MACRO_TAGS = new Set(['TABLE', 'THEAD', 'TBODY', 'TFOOT', 'TR']);
    const isMacroLayout = (el) => {
        if (MACRO_TAGS.has(el.tagName)) return true;
        const cls = (typeof el.className === 'string') ? el.className.toLowerCase() : '';
        return MACRO_WORDS.some(w => cls.includes(w));
    };
    document.querySelectorAll('[class*="sale-price"]').forEach(saleEl => {
        let container = saleEl.parentElement;
        let depth = 0;
        while (container && depth < MAX_ASCENT) {
            const regulars = container.querySelectorAll('[class*="regular-price"]');
            if (regulars.length > 0) {
                regulars.forEach(n => n.remove());
                break;
            }
            const next = container.parentElement;
            if (!next || isMacroLayout(next)) break;
            container = next;
            depth++;
        }
    });
}"""


# Fast-path signal for _wait_for_render(): a price already exists in the DOM, so an
# extraction tier can succeed now — stop waiting immediately. This is also the escape
# hatch for pages whose text never settles (lazy reviews / recommendation carousels keep
# appending) — without it they'd burn the full render-wait cap even though the price was
# ready at first paint. (The Tier 3 / body-text clause that used to live here was removed:
# a fixed length threshold trips on page chrome before the product renders — see
# _wait_for_render, which waits for the text to *settle* instead.)
_HAS_PRICE_SIGNAL_SCRIPT = """() => {
    // Tier 1 signal: a JSON-LD block that actually carries price/offer data.
    for (const s of document.querySelectorAll('script[type="application/ld+json"]'))
        if (/"(price|offers|Offer)"/.test(s.textContent || '')) return true;
    // Structured microdata/meta: a valid signal even when not visually rendered.
    if (document.querySelector('[itemprop="price"], meta[property="product:price:amount"]'))
        return true;
    // Tier 2 heuristic: a [class*="price"] element, but only if it's visible AND holds a
    // digit. Visibility skips hidden skeletons; the digit check skips *visible* empty
    // skeletons/placeholders (e.g. <div class="price-skeleton">) that render before the
    // price data is fetched — both would otherwise trip the gate before the price exists.
    for (const el of document.querySelectorAll('[class*="price"]'))
        if (el.checkVisibility && el.checkVisibility() && /[0-9]/.test(el.textContent || '')) return true;
    return false;
}"""

# Length of the page's *visible* text, whitespace-collapsed. innerText (not textContent)
# so hidden display:none templates — which the scraper deliberately keeps in the DOM —
# don't inflate the count and trip the stability check before the real content renders.
_VISIBLE_TEXT_LEN_SCRIPT = """() => (document.body ? (document.body.innerText || '') : '').replace(/\\s+/g, ' ').trim().length"""

# Render-wait tuning. The scraper reads the DOM at domcontentloaded, but SPAs inject product
# data afterward. Absent a price signal, we poll the visible-text length until it stops
# growing (render settled) or the cap elapses. STABLE_POLLS=2 means two consecutive unchanged
# polls: at a 500ms cadence a brief chrome-only phase (e.g. KSP's ₪499 promo banner ~250-500ms,
# before the ₪349 product at ~750ms) can't produce two equal consecutive samples, so it can't
# false-settle on chrome. POLL_MS also bounds how often the layout-forcing innerText read runs.
_RENDER_WAIT_MS = 8000  # overall cap, alongside the goto (30000) / CF-wait (15000) timeouts
_RENDER_POLL_MS = 500
_RENDER_STABLE_POLLS = 2
_RENDER_MIN_CHARS = 20  # floor: never declare an empty/near-empty page settled


class ExtractionSource(str, Enum):
    STRUCTURED = "structured"
    SNIPPET = "snippet"
    FULLTEXT = "fulltext"
    BLOCKED = "blocked"


# Tri-state: "can you get it" (pre-order/back-order/online-only count as available), not "on a
# shelf". UNKNOWN is a real third state — a page with no availability signal is unknown, not
# out of stock. Lowercase wire values like ExtractionSource; the backend's
# accept-case-insensitive-enums maps them to the Java AvailabilityStatus.
class AvailabilityStatus(str, Enum):
    AVAILABLE = "available"
    UNAVAILABLE = "unavailable"
    UNKNOWN = "unknown"


class ScrapeRequest(BaseModel):
    url: str


class PriceData(BaseModel):
    price: float
    currency: str
    availability: AvailabilityStatus = AvailabilityStatus.UNKNOWN

    @field_validator("availability", mode="before")
    @classmethod
    def _coerce_availability(cls, v):
        # The JS normalizer emits a canonical token, but be defensive: an unrecognized / blank /
        # None value becomes UNKNOWN rather than raising a ValidationError that would 500 the scrape.
        if isinstance(v, AvailabilityStatus):
            return v
        if v is None:
            return AvailabilityStatus.UNKNOWN
        try:
            return AvailabilityStatus(str(v).strip().lower())
        except ValueError:
            return AvailabilityStatus.UNKNOWN


class ShopNameProposal(BaseModel):
    # The scraper's proposed shop name and how confident the signal is: strong = a site-level
    # signal (og:site_name / JSON-LD Organization), weak = a <title> guess. A proposal, not the
    # final name — the backend resolver decides the stored name (a curated/learned mapping can
    # override even a strong proposal). See _SITE_NAME_SCRIPT.
    name: str
    strong: bool


class ScrapeResponse(BaseModel):
    extractionSource: ExtractionSource
    priceData: PriceData | None = None
    snippet: str | None = None
    innerText: str | None = None
    blockedReason: str | None = None
    shopNameProposal: ShopNameProposal | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global browser
    # async with guarantees the Playwright driver process is stopped even if
    # chromium.launch() or browser.close() raises — otherwise a failed launch or a
    # close() against an already-dead Chromium would orphan the Node driver process.
    async with async_playwright() as p:
        # --disable-blink-features=AutomationControlled hides the navigator.webdriver=true
        # signal that Cloudflare and similar walls fingerprint to detect Playwright-driven
        # Chrome. Native engine flag (not a JS patch) — Cloudflare's anti-bot can detect
        # prototype-pollution via add_init_script, so we set it at launch instead.
        # --no-sandbox + --disable-dev-shm-usage stay; they're container hygiene, not stealth.
        browser = await p.chromium.launch(
            headless=True,
            args=[
                "--no-sandbox",
                "--disable-dev-shm-usage",
                "--disable-blink-features=AutomationControlled",
            ],
        )
        try:
            yield
        finally:
            # Null the global before awaiting close() so a close() failure can't leave
            # a stale reference to a dead Browser visible to a later lifespan restart
            # (test suites / dev auto-reload reuse the same process).
            to_close = browser
            browser = None
            if to_close is not None:
                await to_close.close()


app = FastAPI(lifespan=lifespan)


@app.middleware("http")
async def correlation_id_middleware(request: Request, call_next):
    cid = request.headers.get("X-Correlation-ID") or str(uuid.uuid4())
    token = _correlation_id.set(cid)
    try:
        response = await call_next(request)
        response.headers["X-Correlation-ID"] = cid
        return response
    finally:
        _correlation_id.reset(token)


async def _extract_snippet(page) -> str | None:
    parts = []

    for prop in [("product:price:amount", "content"), ("product:price:currency", "content")]:
        try:
            el = await page.query_selector(f'meta[property="{prop[0]}"]')
            if el:
                val = await el.get_attribute(prop[1])
                if val and val.strip():
                    parts.append(val.strip())
        except Exception:
            pass

    for itemprop in ["price", "priceCurrency", "availability"]:
        try:
            el = await page.query_selector(f'[itemprop="{itemprop}"]')
            if el:
                val = await el.get_attribute("content") or await el.inner_text()
                if val and val.strip():
                    parts.append(val.strip())
        except Exception:
            pass

    # Visible-only and length-capped: skips hidden out-of-stock templates and
    # parent containers that match via ancestor class but contain whole descriptions.
    MAX_ELEMENT_CHARS = 200
    PER_SELECTOR_LIMIT = 3
    PRICE_SELECTOR = '[class*="price"]'
    # Deliberately no [class*="delivery"]/[class*="shipping"]: those elements carry
    # shipping COSTS (e.g. KSP's "1-6 ימי עסקים ₪0 | ₪10 | ₪30"), which pollute price
    # extraction and — on a page whose price uses a non-semantic class — can form a
    # price-less snippet that wrongly short-circuits the FULLTEXT fallback. Availability
    # timing from delivery text is still covered by FULLTEXT and the stock selectors below.
    css_selectors = [
        PRICE_SELECTOR,
        '[class*="stock"]',
        '[class*="availability"]',
        '[id*="availability"]',
        '[id*="stock"]',
    ]
    # Decoy prices (strikethrough + paired .regular-price) have already been
    # removed globally by _STRIP_DECOY_PRICES_SCRIPT before we got here.
    for selector in css_selectors:
        try:
            els = await page.query_selector_all(selector)
            taken = 0
            for el in els:
                if taken >= PER_SELECTOR_LIMIT:
                    break
                try:
                    if not await el.is_visible():
                        continue
                    text = (await el.inner_text() or "").strip()
                    if not text or len(text) > MAX_ELEMENT_CHARS:
                        continue
                    parts.append(text)
                    taken += 1
                except Exception:
                    continue
        except Exception:
            pass

    # Assumes a PDP URL (single product container). On a category listing this would
    # read the first product's flag, which may not match the URL the user submitted.
    # WooCommerce-style availability flag on the product container. The state is
    # encoded as a class word ("instock" or "outofstock"), not visible text — and
    # the container's text is the entire product card, far over MAX_ELEMENT_CHARS.
    # Read the class attribute directly and synthesize an English phrase the LLM
    # can parse regardless of page locale. Check outofstock first: if both ever
    # co-exist, false-negative on availability is safer than false-positive.
    try:
        el = await page.query_selector('[class~="outofstock"], [class~="instock"]')
        if el:
            cls = (await el.get_attribute("class") or "").split()
            if "outofstock" in cls:
                parts.append("This product is currently out of stock")
            elif "instock" in cls:
                parts.append("This product is currently in stock")
    except Exception:
        pass

    deduped = list(dict.fromkeys(parts))
    return " | ".join(deduped) if deduped else None


async def _extract_site_name(page) -> ShopNameProposal | None:
    # Best-effort store-name detection: any browser-eval failure yields None so it can never block
    # a scrape. Returns a ShopNameProposal (name + strong) or None — see _SITE_NAME_SCRIPT.
    try:
        result = await page.evaluate(_SITE_NAME_SCRIPT)
    except Exception:
        return None
    if not result or not result.get("name"):
        return None
    return ShopNameProposal(name=result["name"], strong=bool(result.get("strong")))


_CF_CHALLENGE_TITLES = (
    "just a moment",
    "attention required! | cloudflare",
)

# Linux Chrome UA matched to the Docker container's actual OS — sending a
# Windows/Mac UA from a Linux box creates a JA3/UA mismatch that anti-bot
# walls fingerprint on. Pinned UA string will drift from real Chrome over
# time; bump when CF-protected sites start blocking again.
_BROWSER_USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
    "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
)


async def _detect_block(page, response) -> tuple[bool, str | None]:
    # Checks (in order): HTTP 403 + cf-mitigated: challenge header; CF challenge
    # page title; window._cf_chl_opt presence. Returns (True, reason) on first hit.
    # `reason` always carries the cf-ray when we can find it, so a live block wave
    # is debuggable end-to-end from the API response back through Cloudflare logs.
    cf_ray = "unknown"
    if response is not None:
        try:
            cf_ray = response.headers.get("cf-ray", "unknown")
        except Exception:
            pass

    if response is not None and getattr(response, "status", None) == 403:
        try:
            mitigated = (response.headers.get("cf-mitigated", "") or "").lower()
            if "challenge" in mitigated:
                return True, f"cloudflare-managed:cf-ray={cf_ray}"
        except Exception:
            pass

    try:
        title = ((await page.title()) or "").strip().lower()
        if any(title.startswith(t) or t in title for t in _CF_CHALLENGE_TITLES):
            return True, f"cloudflare-challenge-title:cf-ray={cf_ray}"
    except Exception:
        pass

    try:
        has_cf_chl = await page.evaluate("() => typeof window._cf_chl_opt !== 'undefined'")
        if has_cf_chl:
            return True, f"cloudflare-managed:_cf_chl_opt-present:cf-ray={cf_ray}"
    except Exception:
        pass

    # AWS WAF Bot Control / Captcha challenge interstitial. Served as HTTP 202
    # with a ~2 KB shell that sets window.gokuProps and window.awsWafCookieDomainList
    # — both are AWS-internal identifiers, neither appears on normal product pages
    # (verified against thomann.de, google.com, and a 200-served Amazon PDP).
    # No retry/wait loop like the CF case: AWS's JS challenge mints aws-waf-token
    # via heavy obfuscated code that doesn't complete in our stealth context.
    if response is not None and getattr(response, "status", None) == 202:
        try:
            html = await page.content()
            if "gokuProps" in html or "awsWafCookieDomainList" in html:
                return True, "aws-waf-challenge:status=202"
        except Exception:
            pass

    return False, None


async def _wait_for_render(
    page,
    timeout_ms: int,
    poll_ms: int = _RENDER_POLL_MS,
    stable_polls: int = _RENDER_STABLE_POLLS,
    min_chars: int = _RENDER_MIN_CHARS,
) -> None:
    # Wait for the page to finish rendering before extraction. goto returns at
    # domcontentloaded (initial HTML parsed); an SPA injects its product data later via
    # async JS. We can't key on "a price appeared" to stop, because decoy/promo prices often
    # render with the page chrome before the real one (KSP shows a ₪499 coupon banner ~250ms
    # before the ₪349 product at ~750ms). So absent an immediate price signal, we wait for the
    # visible-text length to *stop changing* — the render has settled. Best-effort: on timeout
    # or a destroyed execution context we just proceed with whatever has rendered so far.
    deadline = time.monotonic() + timeout_ms / 1000
    last_len = -1
    unchanged = 0
    while time.monotonic() < deadline:
        # Narrow try around the volatile browser evals only, so a genuine Python logic
        # bug below (e.g. a TypeError) still surfaces a traceback instead of being swallowed.
        try:
            if await page.evaluate(_HAS_PRICE_SIGNAL_SCRIPT):
                return
            length = await page.evaluate(_VISIBLE_TEXT_LEN_SCRIPT)
        except Exception:
            return
        if length == last_len:
            unchanged += 1
            if unchanged >= stable_polls and length >= min_chars:
                return
        else:
            last_len = length
            unchanged = 0
        # Separate guard: the page closing/navigating during the wait (TargetClosedError)
        # must not crash the scrape — return best-effort, same as the eval failure above.
        try:
            await page.wait_for_timeout(poll_ms)
        except Exception:
            return


def _snippet_has_useful_content(snippet: str) -> bool:
    # A snippet is useful only if it has descriptive text (alphabetic characters,
    # Unicode-aware so Hebrew/Latin both count). Pure currency strings like "₪1,025"
    # are killed by the alpha check; the length floor catches very short bot-wall
    # fragments without rejecting clean structured snippets like "100.00 | USD | OOS"
    # (18 chars).
    return len(snippet) >= 15 and any(c.isalpha() for c in snippet)


@app.post("/scrape", response_model=ScrapeResponse)
async def scrape(request: ScrapeRequest):
    if not request.url.startswith(("http://", "https://")):
        raise HTTPException(status_code=400, detail="URL must use http or https scheme")

    # browser is None before startup finishes launching it and again once the lifespan
    # finally nulls it during shutdown; is_connected() is False if Chromium crashed or was
    # OOM-killed while the global still points at it. Serve a deterministic 503 in those
    # windows rather than letting new_context() blow up with an opaque NoneType/500.
    # (is_connected is a method — `not browser.is_connected` would be a permanent no-op.)
    if browser is None or not browser.is_connected():
        raise HTTPException(status_code=503, detail="Browser is not initialized or has been closed")

    # Realistic viewport/locale/Accept-Language pair with _BROWSER_USER_AGENT.
    context = await browser.new_context(
        user_agent=_BROWSER_USER_AGENT,
        locale="en-US",
        viewport={"width": 1920, "height": 1080},
        extra_http_headers={
            "Accept-Language": "en-US,en;q=0.9",
        },
    )
    try:
        page = await context.new_page()
        response = await page.goto(request.url, wait_until="domcontentloaded", timeout=30000)

        # Bot-wall detection. AWS WAF challenges fail fast — their JS challenge
        # mints aws-waf-token via heavy obfuscated code that doesn't complete in
        # our stealth context, so waiting is pointless. CF managed challenges
        # sometimes self-resolve in our stealth context, so we give them up to
        # 15s before short-circuiting. Either way, BLOCKED skips tiers 1/2/3.
        blocked, reason = await _detect_block(page, response)
        if blocked:
            if reason and reason.startswith("aws-waf-challenge"):
                logging.getLogger(__name__).info(
                    "scrape blocked url=%s reason=%s", request.url, reason
                )
                return ScrapeResponse(
                    extractionSource=ExtractionSource.BLOCKED,
                    blockedReason=reason,
                )
            try:
                await page.wait_for_function(
                    "() => !window._cf_chl_opt "
                    "&& !document.title.toLowerCase().startsWith('just a moment')",
                    timeout=15000,
                )
            except Exception:
                # wait_for_function also throws if the challenge navigates the
                # page on success (execution context destroyed). Re-check DOM
                # signals before concluding we're still blocked.
                still_blocked, _ = await _detect_block(page, None)
                if still_blocked:
                    logging.getLogger(__name__).info(
                        "scrape blocked url=%s reason=%s", request.url, reason
                    )
                    return ScrapeResponse(
                        extractionSource=ExtractionSource.BLOCKED,
                        blockedReason=reason,
                    )

        # Hide chrome (nav/footer/cookie/banner/ads) BEFORE the render-wait so the stabilization
        # signal tracks product content, not chrome that renders early and would false-settle the
        # gate. Injects a display:none stylesheet (not node removal / inline styles): non-destructive
        # so SPA hydration can't crash, and reactive so re-rendered chrome stays hidden. <script>
        # stays intact for Tier-1 JSON-LD below.
        try:
            await page.evaluate(_HIDE_CHROME_SCRIPT)
        except Exception:
            pass

        # Wait for content to render. goto returns at domcontentloaded (initial HTML
        # parsed); SPAs inject product data afterward via async JS. Runs only for
        # non-blocked pages — a bot wall never produces price content, so it would
        # otherwise burn the full timeout. Must precede DOM pruning (which strips the
        # <script> tags JSON-LD lives in) and strip-decoy.
        await _wait_for_render(page, _RENDER_WAIT_MS)

        # Pre-Tier 1: strip decoy prices (strikethrough MSRP + paired .regular-price)
        # from the rendered DOM. Safe before structured-data because it does not
        # touch <script> tags — JSON-LD remains intact. Cleans the DOM body for
        # microdata's innerText reads and for the Tier 2 snippet.
        try:
            await page.evaluate(_STRIP_DECOY_PRICES_SCRIPT)
        except Exception:
            pass

        # Store-name detection — metadata only, computed once here so it rides on every
        # non-blocked response (STRUCTURED/SNIPPET/FULLTEXT). Must run before the Tier-1 early
        # return below and before _DOM_PRUNE_SCRIPT (which strips the <script> tags JSON-LD
        # lives in).
        shop_name_proposal = await _extract_site_name(page)

        # Tier 1: Schema.org structured data (JSON-LD then Microdata) — must run
        # before DOM pruning, which removes <script> tags used by JSON-LD.
        try:
            result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
            if result:
                return ScrapeResponse(
                    extractionSource=ExtractionSource.STRUCTURED,
                    priceData=PriceData(**result),
                    shopNameProposal=shop_name_proposal,
                )
        except Exception:
            pass

        # Pre-step: prune noise from DOM (nav/footer/ads/scripts) before Tier 2 and 3
        try:
            await page.evaluate(_DOM_PRUNE_SCRIPT)
        except Exception:
            pass

        # Tier 2: CSS/meta selectors — gate on quality so bot-walled / pure-price
        # snippets fall through to FULLTEXT instead of producing low-signal LLM input.
        try:
            snippet = await _extract_snippet(page)
            if snippet and _snippet_has_useful_content(snippet):
                return ScrapeResponse(
                    extractionSource=ExtractionSource.SNIPPET,
                    snippet=snippet,
                    shopNameProposal=shop_name_proposal,
                )
        except Exception:
            pass

        # Tier 3: pruned innerText fallback
        inner_text = await page.inner_text("body")
        return ScrapeResponse(
            extractionSource=ExtractionSource.FULLTEXT,
            innerText=inner_text,
            shopNameProposal=shop_name_proposal,
        )

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e)) from e
    finally:
        await context.close()


@app.get("/health")
async def health():
    return {"status": "ok"}
