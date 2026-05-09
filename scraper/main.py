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

# JavaScript run via page.evaluate() to extract structured price data from JSON-LD
_JSON_LD_SCRIPT = """() => {
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
                    const rawPrice = offer.price ?? offer.lowPrice;
                    const raw = String(rawPrice).replace(/[^0-9.,]/g, '');
                    const lastDot = raw.lastIndexOf('.');
                    const lastComma = raw.lastIndexOf(',');
                    const decimalSep = lastDot > lastComma ? '.' : (lastComma > lastDot ? ',' : null);
                    const cleanPrice = decimalSep === null
                        ? raw.replace(/[^0-9]/g, '')
                        : raw.substring(0, raw.lastIndexOf(decimalSep)).replace(/[^0-9]/g, '') + '.' + raw.substring(raw.lastIndexOf(decimalSep) + 1);
                    const price = parseFloat(cleanPrice);
                    const currency = offer.priceCurrency;
                    const availability = offer.availability || '';
                    const inStockUris = ['instock', 'limitedavailability', 'onlineonly', 'presale'];
                    if (!isNaN(price) && price > 0 && currency) {
                        return {
                            price: price,
                            currency: currency,
                            available: inStockUris.some(s => availability.toLowerCase().includes(s))
                        };
                    }
                }
            }
        } catch (e) {}
    }
    return null;
}"""


class ExtractionSource(str, Enum):
    STRUCTURED = "structured"
    SNIPPET = "snippet"
    FULLTEXT = "fulltext"


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


@asynccontextmanager
async def lifespan(app: FastAPI):
    global playwright_instance, browser
    playwright_instance = await async_playwright().start()
    browser = await playwright_instance.chromium.launch(
        headless=True,
        args=["--no-sandbox", "--disable-dev-shm-usage"],
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
    css_selectors = [
        '[class*="price"]',
        '[class*="stock"]',
        '[class*="availability"]',
        '[class*="delivery"]',
        '[id*="availability"]',
        '[id*="stock"]',
    ]
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


def _snippet_has_useful_content(snippet: str) -> bool:
    # A snippet is useful only if it has descriptive text (alphabetic characters,
    # Unicode-aware so Hebrew/Latin both count). Pure price strings like "₪1,025"
    # lack availability info and should fall through to FULLTEXT.
    return len(snippet) >= 30 and any(c.isalpha() for c in snippet)


@app.post("/scrape", response_model=ScrapeResponse)
async def scrape(request: ScrapeRequest):
    if not request.url.startswith(("http://", "https://")):
        raise HTTPException(status_code=400, detail="URL must use http or https scheme")

    context = await browser.new_context()
    try:
        page = await context.new_page()
        await page.goto(request.url, wait_until="domcontentloaded", timeout=30000)

        # Tier 1: JSON-LD structured data — must run before DOM pruning, which removes script tags
        try:
            result = await page.evaluate(_JSON_LD_SCRIPT)
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
