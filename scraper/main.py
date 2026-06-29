import contextvars
import logging
import time
import uuid
from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from playwright.async_api import Browser, async_playwright

from browser_scripts import load_script

# Re-exported for `from main import ...` consumers (tests). DTOs live in models.py so site
# handlers (sites/ksp.py) can import them without a circular import back to main.
from models import (
    AvailabilityStatus,  # noqa: F401 — re-export only (reaches the wire via PriceData)
    ExtractionSource,
    PriceData,
    ScrapeRequest,
    ScrapeResponse,
    ShopNameProposal,
)
from sites import ksp

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
_DOM_PRUNE_SCRIPT = load_script("dom_prune")

# Chrome hider: injects a <style> that display:none's the _DOM_PRUNE_SCRIPT selectors MINUS
# <script>/<noscript>, run before _wait_for_render. innerText (and checkVisibility) honor computed
# style, so hidden chrome leaves the render-settle measurement — the gate tracks product content,
# not nav/footer/cookie/promo chrome that renders early and would false-settle it.
#
# A *stylesheet rule* rather than removing nodes or setting inline styles, because:
#   - non-destructive: nodes stay in the DOM, so removing-mid-hydration crashes (framework refs /
#     querySelector null-derefs) can't happen;
#   - reactive: the rule applies to *any* matching node, so SPA re-renders that tear down and
#     recreate nav/banner/footer get hidden too — inline styles would be lost on the new nodes
#     and let chrome back into innerText, re-settling the gate on chrome.
# <script> stays intact for Tier-1 JSON-LD; the full _DOM_PRUNE_SCRIPT removes the hidden nodes
# post-Tier-1. document.head may not exist this early, so fall back to documentElement.
_HIDE_CHROME_SCRIPT = load_script("hide_chrome")

# JavaScript run via page.evaluate() to extract structured price data. Tries
# JSON-LD first (Schema.org as embedded script); if that yields nothing, falls
# back to Schema.org Microdata (itemprop/itemtype attributes on the HTML itself).
_STRUCTURED_DATA_SCRIPT = load_script("structured_data")

# JavaScript run via page.evaluate() to detect the STORE/site name (issue #33), tiered by
# confidence. og:site_name and Schema.org Organization/WebSite/Store are site-level signals
# ("strong" — safe to persist as the domain's name); the <title> heuristic is a "weak" last
# resort. A marketplace seller (offers.seller.name) is deliberately NOT read here: it names the
# third-party seller of one listing, not the storefront, and would poison the shared domain
# mapping — capturing it belongs in a separate per-listing field (follow-up issue). JSON-LD
# `brand` is never read either — it's the manufacturer ("Sony"), not the shop. Returns
# {name, strong} or null.
_SITE_NAME_SCRIPT = load_script("site_name")

# JavaScript run via page.evaluate() to remove decoy prices (strikethrough MSRP
# and paired .regular-price) from the rendered DOM. Runs before Tier 1 so
# microdata's innerText-based reads see clean values; runs before Tier 2 so the
# snippet doesn't flatten both prices into one string. Safe to run before
# _DOM_PRUNE_SCRIPT because it does not touch <script> tags — JSON-LD survives.
_STRIP_DECOY_PRICES_SCRIPT = load_script("strip_decoy_prices")


# Fast-path signal for _wait_for_render(): a price already exists in the DOM, so an
# extraction tier can succeed now — stop waiting immediately. This is also the escape
# hatch for pages whose text never settles (lazy reviews / recommendation carousels keep
# appending) — without it they'd burn the full render-wait cap even though the price was
# ready at first paint. (The Tier 3 / body-text clause that used to live here was removed:
# a fixed length threshold trips on page chrome before the product renders — see
# _wait_for_render, which waits for the text to *settle* instead.)
_HAS_PRICE_SIGNAL_SCRIPT = load_script("has_price_signal")

# Length of the page's *visible* text, whitespace-collapsed. innerText (not textContent)
# so hidden display:none templates — which the scraper deliberately keeps in the DOM —
# don't inflate the count and trip the stability check before the real content renders.
_VISIBLE_TEXT_LEN_SCRIPT = load_script("visible_text_len")

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

        # KSP: attach the price-SSE capture BEFORE goto — the stream fires during page
        # load and can't be replayed (Cloudflare 403s out-of-band requests). Attached
        # UNCONDITIONALLY: it's a passive, host-filtered listener (see attach_sse_capture
        # — one substring check per response, body-read only for a real KSP SSE response),
        # so the cost on non-KSP pages is ~nil. Doing it unconditionally means a non-KSP
        # URL that REDIRECTS into KSP (a shortener/affiliate/share link) still has its
        # page-load SSE captured; the handler only runs when the FINAL page.url is a KSP
        # host (dispatch below), and the listener self-filters to KSP-host responses.
        ksp_cap = ksp.attach_sse_capture(page)

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

        # KSP handler-first (lean): KSP is a network/API extractor — it needs neither
        # chrome-hide nor the generic render gate, so we try it right after bot-wall
        # detection. Dispatch on the FINAL page.url host (post-redirect), so a non-KSP URL
        # that landed on KSP is handled and a non-KSP page never runs the handler (no
        # misleading "no price" warning). On success we return STRUCTURED before any
        # generic work; a clean None (no price on a KSP item page) or an
        # exception falls through to the generic waterfall — logged, so silent KSP degradation is
        # observable.
        if ksp.matches(page.url):
            try:
                ksp_result = await ksp.extract(page, ksp_cap)
                if ksp_result is not None:
                    return ksp_result
                logging.getLogger(__name__).warning(
                    "ksp handler returned no price requested=%s final=%s; falling back to generic",
                    request.url,
                    page.url,
                )
            except Exception:
                logging.getLogger(__name__).warning(
                    "ksp handler failed requested=%s final=%s; falling back",
                    request.url,
                    page.url,
                    exc_info=True,
                )

        # Hide chrome (nav/footer/cookie/banner/ads) BEFORE the render-wait so the
        # stabilization signal tracks product content, not chrome that renders early and
        # would false-settle the gate. Injects a display:none stylesheet (not node removal
        # / inline styles): non-destructive so SPA hydration can't crash, and reactive so
        # re-rendered chrome stays hidden. <script> stays intact for Tier-1 JSON-LD below.
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
