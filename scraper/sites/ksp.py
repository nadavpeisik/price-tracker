"""KSP (ksp.co.il) site-specific extraction handler (#28).

KSP product pages are a ~4 KB SPA shell with no JSON-LD and no price in the initial DOM, so the
generic Tier 1/2/3 waterfall can't isolate the price (it returns a wrong one — Eilat/promo/
carousel). But KSP exposes structured data via interceptable requests:

- PRICE and the stock lookup key both arrive on page load over a Server-Sent Events stream
  (POST /m_action/sse/streams), keyed by the URL item id ("uin").
- AVAILABILITY comes from a stock-by-branch endpoint (GET /m_action/api/mlay/{catalog}), which we
  request ourselves from inside the page once the SSE has told us the catalog id.

Hard rule: intercept or ask from inside the page — never replay out-of-band. Cloudflare 403s a
request made from Playwright's APIRequestContext even though it shares the cookie jar; only a
document-context fetch carries the headers that pass the wall (see browser_scripts/
ksp_branch_stock.js). The pure functions below (matches / parse_uin / parse_item_from_sse /
stock_status) hold all the KSP-specific parsing knowledge and are unit-tested without a browser;
the orchestration (attach_sse_capture / extract / _fetch_stock) drives Playwright.

We used to get availability by clicking the page's "check stock in branches" button and
intercepting the XHR it fired. That broke silently for seven weeks (#196) when KSP retitled the
button, and it could never resolve the case that matters most: an out-of-stock item renders no
stock button at all, so the very items we want to report UNAVAILABLE stayed UNKNOWN. Asking for
the endpoint directly has no such blind spot and no dependency on button copy, MUI's hashed class
names, or React having bound its handler yet.
"""

import asyncio
import json
import logging
import math
import re
import time
from typing import NamedTuple
from urllib.parse import urlparse

from browser_scripts import load_script
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
_PRICE_DEADLINE_S = 3.0
_STOCK_TIMEOUT_MS = 2000

_BRANCH_STOCK_SCRIPT = load_script("ksp_branch_stock")

# A catalog id is a short opaque token, NOT a path — it is network-supplied (it reaches us over
# KSP's SSE stream) and we interpolate it into a URL, so pin the shape here as well as
# encodeURIComponent-ing it browser-side. Live ids are numeric ("362345") or letter-prefixed
# ("F000029"). A shape we don't recognise degrades availability to UNKNOWN, which is honest;
# guessing at it and building a URL out of it is not.
_CATALOG_RE = re.compile(r"^[A-Za-z0-9_-]{1,32}$")

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


def _coerce_catalog(raw) -> str | None:
    """Coerce an SSE ``uinsql`` value to a usable catalog id, else None."""
    if isinstance(raw, bool):  # bool is an int subclass — a flag is not an id
        return None
    if not isinstance(raw, str | int):
        return None
    value = str(raw).strip()
    return value if _CATALOG_RE.fullmatch(value) else None


class SseItem(NamedTuple):
    """What KSP's SSE tells us about one product: its price, and the key to its branch stock.

    ``catalog`` is optional and ``price`` is not, which mirrors how the two are used: no price
    means we hand the page back to the generic waterfall, whereas no catalog only costs us the
    availability lookup.
    """

    price: float
    catalog: str | None


def _item_from_event(payload, uin: str) -> SseItem | None:
    """Our item's price + catalog id from one parsed SSE event payload, or None.

    Only the authoritative single-item ("item.item") payload carries the product's own price —
    pin to it so another event with the same uin can't win. KSP wraps it in an envelope
    ``{requestId, key, route, ok, data}`` with the product at ``data.result.data`` (item id as a
    ``uin`` field, price at ``data.result.data.price`` — verified across 3 live items 2026-06-28).
    The branch-stock endpoint is keyed not by that uin but by KSP's catalog number, which the same
    payload carries as ``uinsql`` (415448 -> 362345; verified across 10 live items 2026-08-19).
    Every level is isinstance-guarded so a shape change is skipped, not raised.
    """
    if not isinstance(payload, dict) or payload.get("key") != "item.item":
        return None
    data = payload.get("data")
    result = data.get("result") if isinstance(data, dict) else None
    product = result.get("data") if isinstance(result, dict) else None
    if not isinstance(product, dict) or str(product.get("uin")) != uin:
        return None
    price = _coerce_price(product.get("price"))
    if price is None:
        return None
    return SseItem(price=price, catalog=_coerce_catalog(product.get("uinsql")))


def parse_item_from_sse(sse_text: str, uin: str) -> SseItem | None:
    """Return the product's price + catalog from a captured SSE body, or None.

    Keyed on the price: an event we can't read a price out of is skipped, so a later well-formed
    event still wins (malformed events are likewise skipped rather than raised).
    """
    for block in _sse_data_blocks(sse_text):
        try:
            payload = json.loads(block)
        except (ValueError, TypeError):
            continue
        item = _item_from_event(payload, uin)
        if item is not None:
            return item
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


async def _drain_item(sse_queue: asyncio.Queue[str], uin: str) -> SseItem | None:
    """Pull SSE bodies off the queue until our uin's item is found or the deadline elapses."""
    deadline = time.monotonic() + _PRICE_DEADLINE_S
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            return None
        try:
            body = await asyncio.wait_for(sse_queue.get(), timeout=remaining)
        except TimeoutError:
            return None
        item = parse_item_from_sse(body, uin)
        if item is not None:
            return item


async def _fetch_stock(page, catalog: str) -> AvailabilityStatus:
    """Best-effort branch-stock lookup. Any failure → UNKNOWN (the price still returns).

    Asks the page to fetch GET /m_action/api/mlay/{catalog} on our behalf — see
    browser_scripts/ksp_branch_stock.js for why it has to be the page that asks. The response
    carries every branch regardless of the region the shopper has picked in KSP's area-selection
    modal: that modal only filters what the UI draws (each branch gained `region`/`region_id`
    fields for it), so there is nothing to select before reading stock.

    Two timeouts on purpose: AbortSignal inside the page bounds the request, and wait_for bounds
    the evaluate itself, so a page that stops answering can't outlive the scrape either way.
    """
    try:
        payload = await asyncio.wait_for(
            page.evaluate(
                _BRANCH_STOCK_SCRIPT, {"catalog": catalog, "timeoutMs": _STOCK_TIMEOUT_MS}
            ),
            timeout=_STOCK_TIMEOUT_MS / 1000 + 1,
        )
        if payload is None:  # non-2xx — degrade honestly rather than fabricate out-of-stock
            return AvailabilityStatus.UNKNOWN
        return stock_status(payload)
    except Exception:
        logger.debug("ksp stock fetch failed catalog=%s", catalog, exc_info=True)
        return AvailabilityStatus.UNKNOWN


async def extract(page, sse_queue: asyncio.Queue[str]) -> ScrapeResponse | None:
    """Return a STRUCTURED ScrapeResponse for a KSP item page, or None to fall back to generic.

    Price first (from the SSE queue): if absent we return None *before* touching the page, so the
    generic fallback runs on a clean page. uin is parsed from the *final* page.url so KSP→KSP
    redirects (scheme/subdomain/legacy path) still resolve. Availability is best-effort → UNKNOWN,
    including when the SSE payload carried no usable catalog id to look stock up by.
    """
    # Verify the FINAL page is still a KSP host — a KSP→non-KSP (open) redirect must not yield a
    # "KSP" structured result from an off-site page (Codex diff-review).
    if not matches(page.url):
        return None
    uin = parse_uin(page.url)
    if uin is None:
        return None
    item = await _drain_item(sse_queue, uin)
    if item is None:
        return None
    availability = (
        await _fetch_stock(page, item.catalog)
        if item.catalog is not None
        else AvailabilityStatus.UNKNOWN
    )
    return ScrapeResponse(
        extractionSource=ExtractionSource.STRUCTURED,
        priceData=PriceData(price=item.price, currency="ILS", availability=availability),
        shopNameProposal=ShopNameProposal(name="KSP", strong=True),
    )
