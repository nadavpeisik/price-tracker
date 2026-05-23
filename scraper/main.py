import contextvars
import logging
import uuid
from contextlib import asynccontextmanager
from enum import Enum

from fastapi import FastAPI, HTTPException, Request
from playwright.async_api import async_playwright, Browser, Playwright
from pydantic import BaseModel

playwright_instance: Playwright = None
browser: Browser = None

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

    const IN_STOCK_URIS = ['instock', 'limitedavailability', 'onlineonly', 'presale', 'preorder'];
    const buildResult = (price, currency, availability) => {
        if (isNaN(price) || price <= 0 || !currency) return null;
        return {
            price: price,
            currency: currency,
            available: IN_STOCK_URIS.some(s => String(availability || '').toLowerCase().includes(s))
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


class ExtractionSource(str, Enum):
    STRUCTURED = "structured"
    SNIPPET = "snippet"
    FULLTEXT = "fulltext"
    BLOCKED = "blocked"


class ScrapeRequest(BaseModel):
    url: str


class PriceData(BaseModel):
    price: float
    currency: str
    available: bool


class ScrapeResponse(BaseModel):
    extractionSource: ExtractionSource
    priceData: PriceData | None = None
    snippet: str | None = None
    innerText: str | None = None
    blockedReason: str | None = None


@asynccontextmanager
async def lifespan(app: FastAPI):
    global playwright_instance, browser
    playwright_instance = await async_playwright().start()
    # --disable-blink-features=AutomationControlled hides the navigator.webdriver=true
    # signal that Cloudflare and similar walls fingerprint to detect Playwright-driven
    # Chrome. Native engine flag (not a JS patch) — Cloudflare's anti-bot can detect
    # prototype-pollution via add_init_script, so we set it at launch instead.
    # --no-sandbox + --disable-dev-shm-usage stay; they're container hygiene, not stealth.
    browser = await playwright_instance.chromium.launch(
        headless=True,
        args=[
            "--no-sandbox",
            "--disable-dev-shm-usage",
            "--disable-blink-features=AutomationControlled",
        ],
    )
    yield
    await browser.close()
    await playwright_instance.stop()


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
    css_selectors = [
        PRICE_SELECTOR,
        '[class*="stock"]',
        '[class*="availability"]',
        '[class*="delivery"]',
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

    return False, None


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

        # Bot-wall detection. If the page is challenged, give the managed-challenge
        # JS up to 15s to self-resolve in our stealth context; if it doesn't clear,
        # short-circuit to BLOCKED and skip tiers 1/2/3 — extracting from a "Just a
        # moment" interstitial would produce nonsense at best.
        blocked, reason = await _detect_block(page, response)
        if blocked:
            try:
                await page.wait_for_function(
                    "() => !window._cf_chl_opt "
                    "&& !document.title.toLowerCase().startsWith('just a moment')",
                    timeout=15000,
                )
            except Exception:
                logging.getLogger(__name__).info(
                    "scrape blocked url=%s reason=%s", request.url, reason
                )
                return ScrapeResponse(
                    extractionSource=ExtractionSource.BLOCKED,
                    blockedReason=reason,
                )

        # Pre-Tier 1: strip decoy prices (strikethrough MSRP + paired .regular-price)
        # from the rendered DOM. Safe before structured-data because it does not
        # touch <script> tags — JSON-LD remains intact. Cleans the DOM body for
        # microdata's innerText reads and for the Tier 2 snippet.
        try:
            await page.evaluate(_STRIP_DECOY_PRICES_SCRIPT)
        except Exception:
            pass

        # Tier 1: Schema.org structured data (JSON-LD then Microdata) — must run
        # before DOM pruning, which removes <script> tags used by JSON-LD.
        try:
            result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
            if result:
                return ScrapeResponse(
                    extractionSource=ExtractionSource.STRUCTURED,
                    priceData=PriceData(**result),
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
                )
        except Exception:
            pass

        # Tier 3: pruned innerText fallback
        inner_text = await page.inner_text("body")
        return ScrapeResponse(
            extractionSource=ExtractionSource.FULLTEXT,
            innerText=inner_text,
        )

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))
    finally:
        await context.close()


@app.get("/health")
async def health():
    return {"status": "ok"}
