"""KSP (ksp.co.il) site-specific extraction handler (#28).

KSP product pages are a ~4 KB SPA shell with no JSON-LD and no price in the initial DOM, so the
generic Tier 1/2/3 waterfall can't isolate the price (it returns a wrong one — Eilat/promo/
carousel). But KSP exposes structured data via interceptable requests:

- PRICE arrives on page load over a Server-Sent Events stream (POST /m_action/sse/streams),
  keyed by the URL item id ("uin").
- AVAILABILITY comes from a stock-by-branch XHR (GET /m_action/api/mlay/{catalog}) the page fires
  when the "check stock in branches" button is clicked.

Hard rule: intercept, never replay — out-of-band requests get a Cloudflare 403, so we capture the
page's own requests. The pure functions below (matches / parse_uin / parse_price_from_sse /
stock_status) hold all the KSP-specific parsing knowledge and are unit-tested without a browser;
the orchestration (attach_sse_capture / extract / _fetch_stock) drives Playwright.
"""

import asyncio
import json
import logging
import math
import re
import time
from urllib.parse import urlparse

from playwright.async_api import TimeoutError as PlaywrightTimeoutError

from models import (
    AvailabilityStatus,
    ExtractionSource,
    PriceData,
    ScrapeResponse,
    ShopNameProposal,
)

logger = logging.getLogger(__name__)

_KSP_HOST = "ksp.co.il"
_SSE_URL_FRAGMENT = "/m_action/sse/streams"
_MLAY_URL_GLOB = "**/m_action/api/mlay/**"
_STOCK_BUTTON_TEXT = "לבדיקת מלאי"
_PRICE_DEADLINE_S = 3.0
_STOCK_TIMEOUT_MS = 2000
_CLICK_RETRY_WAIT_MS = 500

# Matches /web/item/<digits> and the legacy /item/<digits>. The id must be a COMPLETE path segment
# (followed by `/` or end) so `/web/item/123abc` doesn't parse as 123 — but a trailing slug segment
# (`/web/item/415448/<name>`, which real KSP links carry) is still allowed. Query is in url.query,
# not url.path, so it's already excluded.
_ITEM_PATH_RE = re.compile(r"/(?:web/)?item/(\d+)(?:/|$)")


def matches(url: str) -> bool:
    """True if url is on ksp.co.il or any subdomain (case + trailing-dot normalized)."""
    host = (urlparse(url).hostname or "").rstrip(".").lower()
    return host == _KSP_HOST or host.endswith("." + _KSP_HOST)


def parse_uin(url: str) -> str | None:
    """Extract the KSP item id ("uin") from a product URL, or None if it isn't an item page."""
    match = _ITEM_PATH_RE.search(urlparse(url).path)
    return match.group(1) if match else None


def _coerce_price(raw) -> float | None:
    """Coerce an SSE price value to a positive float, else None. Tolerates int/float/str."""
    if isinstance(raw, bool):  # bool is an int subclass — never a valid price
        return None
    if isinstance(raw, int | float):
        value = float(raw)
    elif isinstance(raw, str):
        try:
            value = float(raw.replace(",", "").strip())
        except ValueError:
            return None
    else:
        return None
    return value if math.isfinite(value) and value > 0 else None


def _sse_data_blocks(sse_text: str):
    """Yield each SSE event's concatenated `data:` payload (CRLF-tolerant, multi-line per spec)."""
    text = sse_text.replace("\r\n", "\n").replace("\r", "\n")
    for event in text.split("\n\n"):
        data_lines = []
        for line in event.split("\n"):
            if line.startswith("data:"):
                value = line[len("data:") :]
                # SSE spec: strip at most ONE leading space, not all whitespace (.lstrip() would
                # over-strip a value where leading whitespace is significant). Harmless for KSP's
                # JSON payloads, but keeps this a correct general SSE parser (gemini-code-assist).
                data_lines.append(value[1:] if value.startswith(" ") else value)
        if data_lines:
            yield "\n".join(data_lines)


def _price_from_event(payload, uin: str) -> float | None:
    """Our item's positive price from one parsed SSE event payload, or None.

    Only the authoritative single-item ("item.item") payload carries the product's own price —
    pin to it so another event with the same uin can't win. KSP wraps it in an envelope
    ``{requestId, key, route, ok, data}`` with the product at ``data.result.data`` (item id as a
    ``uin`` field, price at ``data.result.data.price`` — verified across 3 live items 2026-06-28).
    Every level is isinstance-guarded so a shape change is skipped, not raised.
    """
    if not isinstance(payload, dict) or payload.get("key") != "item.item":
        return None
    data = payload.get("data")
    result = data.get("result") if isinstance(data, dict) else None
    product = result.get("data") if isinstance(result, dict) else None
    if not isinstance(product, dict) or str(product.get("uin")) != uin:
        return None
    return _coerce_price(product.get("price"))


def parse_price_from_sse(sse_text: str, uin: str) -> float | None:
    """Return the product's price from a captured SSE body, or None (skips malformed events)."""
    for block in _sse_data_blocks(sse_text):
        try:
            payload = json.loads(block)
        except (ValueError, TypeError):
            continue
        price = _price_from_event(payload, uin)
        if price is not None:
            return price
    return None


def _parse_qnt(raw) -> int | None:
    """Parse a branch stock quantity to int, or None if missing/unparseable."""
    if isinstance(raw, bool):  # bool is an int subclass — a boolean flag isn't a stock count
        return None
    try:
        return int(raw)
    except (ValueError, TypeError):
        return None


def stock_status(mlay_json) -> AvailabilityStatus:
    """Roll up the per-branch mlay stock response to a tri-state AvailabilityStatus.

    Tri-state-honest — scan ALL branches, then decide (don't short-circuit on the first
    unparseable branch, since a later one might have stock):
      1. any branch qnt > 0           -> AVAILABLE (definitive)
      2. else any branch qnt missing/unparseable, or stores missing/malformed/empty -> UNKNOWN
         (can't rule out stock in an unreadable branch; don't fabricate out-of-stock)
      3. else (every branch parsed, all <= 0) -> UNAVAILABLE
    """
    result = mlay_json.get("result") if isinstance(mlay_json, dict) else None
    stores = result.get("stores") if isinstance(result, dict) else None
    if not isinstance(stores, dict) or not stores:
        return AvailabilityStatus.UNKNOWN

    any_unparseable = False
    for branch in stores.values():
        qnt = _parse_qnt(branch.get("qnt")) if isinstance(branch, dict) else None
        if qnt is None:
            any_unparseable = True
        elif qnt > 0:
            return AvailabilityStatus.AVAILABLE
    return AvailabilityStatus.UNKNOWN if any_unparseable else AvailabilityStatus.UNAVAILABLE


# --- Orchestration (Playwright) --------------------------------------------------------------
def attach_sse_capture(page) -> asyncio.Queue[str]:
    """Capture KSP's price SSE bodies onto a queue. MUST be called before page.goto().

    KSP pushes product data over POST /m_action/sse/streams during page load; we intercept the
    page's own response (replaying it ourselves gets a Cloudflare 403). A Queue is race-free for
    multiple/late responses — no event-clear bookkeeping. The per-request browser context is
    closed after the scrape, which disposes this listener, so nothing accumulates across requests.
    """
    queue: asyncio.Queue[str] = asyncio.Queue()

    async def _on_response(response):
        # Capture only KSP's OWN SSE responses — verify the host, not just the path, so a
        # KSP→non-KSP redirect can't feed us a spoofed price (Codex diff-review).
        if _SSE_URL_FRAGMENT not in response.url or not matches(response.url):
            return
        try:
            body = await response.text()
        except Exception:
            # Page/context torn down mid-read (Target closed / navigation). Swallow at debug — it
            # can't crash the page, and an empty queue just means we fall back to the generic path.
            logger.debug("ksp sse read failed url=%s", response.url, exc_info=True)
            return
        queue.put_nowait(body)

    page.on("response", _on_response)
    return queue


async def _drain_price(sse_queue: asyncio.Queue[str], uin: str) -> float | None:
    """Pull SSE bodies off the queue until our uin's price is found or the deadline elapses."""
    deadline = time.monotonic() + _PRICE_DEADLINE_S
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return None
        try:
            body = await asyncio.wait_for(sse_queue.get(), timeout=remaining)
        except TimeoutError:
            return None
        price = parse_price_from_sse(body, uin)
        if price is not None:
            return price


async def _fetch_stock(page) -> AvailabilityStatus:
    """Best-effort branch-stock lookup. Any failure → UNKNOWN (price still returns).

    Click the (visible) "check stock in branches" button so the page fires GET .../mlay/{catalog}.
    `expect_request` arms BEFORE each click (on context-manager enter) so the request the click
    fires is caught — keyed on the *request*, not the response, so a slow server can't trigger a
    duplicate click; a click that fires no request (button present but React hasn't bound its
    handler) retries once. We then read **that specific request's** response via
    `request.response()` — not "any matching mlay response in a window" — so a concurrent/background
    mlay can't be misbound (CodeRabbit). The `expect_*` CMs remove their own listeners on exit.
    """
    try:
        button = page.get_by_text(_STOCK_BUTTON_TEXT).filter(visible=True).first
        await button.wait_for(state="visible", timeout=_STOCK_TIMEOUT_MS)
        request = None
        for attempt in range(2):
            try:
                async with page.expect_request(_MLAY_URL_GLOB, timeout=_CLICK_RETRY_WAIT_MS) as req:
                    await button.evaluate("el => el.click()")
                request = await req.value
                break
            except PlaywrightTimeoutError:
                if attempt == 1:
                    raise
        response = await asyncio.wait_for(request.response(), timeout=_STOCK_TIMEOUT_MS / 1000)
        return stock_status(await response.json())
    except Exception:
        logger.debug("ksp stock fetch failed", exc_info=True)
        return AvailabilityStatus.UNKNOWN


async def extract(page, sse_queue: asyncio.Queue[str]) -> ScrapeResponse | None:
    """Return a STRUCTURED ScrapeResponse for a KSP item page, or None to fall back to generic.

    Price first (from the SSE queue): if absent we return None *before* any DOM interaction, so the
    generic fallback runs on a clean page. uin is parsed from the *final* page.url so KSP→KSP
    redirects (scheme/subdomain/legacy path) still resolve. Availability is best-effort → UNKNOWN.
    """
    # Verify the FINAL page is still a KSP host — a KSP→non-KSP (open) redirect must not yield a
    # "KSP" structured result from an off-site page (Codex diff-review).
    if not matches(page.url):
        return None
    uin = parse_uin(page.url)
    if uin is None:
        return None
    price = await _drain_price(sse_queue, uin)
    if price is None:
        return None
    availability = await _fetch_stock(page)
    return ScrapeResponse(
        extractionSource=ExtractionSource.STRUCTURED,
        priceData=PriceData(price=price, currency="ILS", availability=availability),
        shopNameProposal=ShopNameProposal(name="KSP", strong=True),
    )
