"""Tests for the KSP site handler (sites/ksp.py).

This file holds the pure-function unit tests (no browser). The Playwright integration tests for
the orchestration (attach_sse_capture / extract / _fetch_stock) live alongside them once that
code lands.
"""

import asyncio
import json

import pytest
import pytest_asyncio
from playwright.async_api import async_playwright

from models import AvailabilityStatus, ExtractionSource
from sites import ksp


# --- matches ---------------------------------------------------------------------------------
@pytest.mark.parametrize(
    "url,expected",
    [
        ("https://ksp.co.il/web/item/1", True),
        ("https://www.ksp.co.il/web/item/1", True),
        ("https://m.ksp.co.il/web/item/1", True),
        ("HTTPS://KSP.CO.IL./web/item/1", True),  # uppercase + trailing dot normalized
        ("https://amazon.com/dp/1", False),
        ("https://ksp.co.il.evil.com/x", False),  # suffix-spoof must not match
        ("https://notksp.co.il/x", False),  # the leading dot in the suffix guards this
    ],
)
def test_matches(url, expected):
    assert ksp.matches(url) is expected


# --- parse_uin -------------------------------------------------------------------------------
@pytest.mark.parametrize(
    "url,expected",
    [
        ("https://ksp.co.il/web/item/415448", "415448"),
        ("https://ksp.co.il/web/item/415448?ref=home", "415448"),  # query ignored
        ("https://ksp.co.il/web/item/415448/some-product-slug", "415448"),  # trailing slug ok
        ("https://ksp.co.il/item/99/", "99"),  # legacy path + trailing slash
        ("https://ksp.co.il/web/item/123abc", None),  # id must be a COMPLETE segment
        ("https://ksp.co.il/web/cat/573", None),  # category, not an item
        ("https://ksp.co.il/", None),
    ],
)
def test_parse_uin(url, expected):
    assert ksp.parse_uin(url) == expected


# --- parse_price_from_sse --------------------------------------------------------------------
def _item_sse(uin, price, sep="\n\n"):
    """Build a realistic KSP single-item ("item.item") SSE body — the shape verified live."""
    payload = {"key": "item.item", "data": {"result": {"data": {"uin": uin, "price": price}}}}
    return f"data: {json.dumps(payload)}{sep}"


# A realistic 2-event stream: the item.item payload for our product, plus a keyed item.bms
# (related-products) payload we must NOT read from.
_SSE = (
    "event: partial\n"
    + _item_sse(415448, 349)
    + 'data: {"key": "item.bms", "data": {"result": {"349185": {"uin": 349185, "price": 1599}}}}\n'
)


def test_price_hit():
    assert ksp.parse_price_from_sse(_SSE, "415448") == 349.0


def test_price_absent_uin():
    assert ksp.parse_price_from_sse(_SSE, "999") is None


def test_price_ignores_keyed_bms_payload():
    # 349185 appears only in the keyed item.bms (related-products) payload, never item.item; we
    # read only the page's own item, so its price must not be returned.
    assert ksp.parse_price_from_sse(_SSE, "349185") is None


def test_price_ignores_non_item_item_event():
    # An event with the item shape + our uin but a non-"item.item" key must be ignored.
    sse = 'data: {"key": "item.other", "data": {"result": {"data": {"uin": 1, "price": 99}}}}\n\n'
    assert ksp.parse_price_from_sse(sse, "1") is None


def test_price_tolerates_malformed_event():
    sse = "data: not json\n\n" + _item_sse(1, 12)
    assert ksp.parse_price_from_sse(sse, "1") == 12.0


def test_price_crlf_boundaries():
    body = _item_sse(1, 99).replace("\n", "\r\n")
    assert ksp.parse_price_from_sse(body, "1") == 99.0


def test_price_string_with_comma():
    assert ksp.parse_price_from_sse(_item_sse(1, "1,299"), "1") == 1299.0


@pytest.mark.parametrize("price", [0, -5, "abc", None, "0"])
def test_price_rejects_nonpositive_or_nonnumeric(price):
    assert ksp.parse_price_from_sse(_item_sse(1, price), "1") is None


def test_price_tolerates_shape_change():
    # data.result is a list, not a dict — skipped, not raised.
    sse = 'data: {"data": {"result": [1, 2, 3]}}\n'
    assert ksp.parse_price_from_sse(sse, "1") is None


# --- stock_status ----------------------------------------------------------------------------
def _stores(qnts):
    return {"result": {"stores": {str(i): {"qnt": q} for i, q in enumerate(qnts)}}}


def test_stock_available():
    assert ksp.stock_status(_stores([0, 3])) is AvailabilityStatus.AVAILABLE


def test_stock_available_string_qnt():
    assert ksp.stock_status(_stores(["5"])) is AvailabilityStatus.AVAILABLE


def test_stock_unavailable_all_zero():
    assert ksp.stock_status(_stores([0, "0"])) is AvailabilityStatus.UNAVAILABLE


def test_stock_available_beats_earlier_unparseable():
    # a later in-stock branch wins over an earlier unreadable one (scan-all, not short-circuit)
    assert ksp.stock_status(_stores(["limited", 2])) is AvailabilityStatus.AVAILABLE


def test_stock_unknown_mixed_unparseable():
    # some 0, some unreadable, none in stock -> can't rule out stock -> UNKNOWN
    assert ksp.stock_status(_stores([0, "limited"])) is AvailabilityStatus.UNKNOWN


def test_stock_unknown_field_rename():
    # every branch lacks a parseable qnt (field renamed) -> UNKNOWN, not fabricated UNAVAILABLE
    j = {"result": {"stores": {"a": {"quantity": 0}, "b": {"quantity": 0}}}}
    assert ksp.stock_status(j) is AvailabilityStatus.UNKNOWN


@pytest.mark.parametrize(
    "payload",
    [
        {"result": {"stores": {}}},  # empty stores
        {"result": {"stores": "nope"}},  # malformed stores
        {"result": {}},  # no stores
        {"oops": 1},  # no result
        "not even a dict",
    ],
)
def test_stock_unknown_for_missing_or_malformed(payload):
    assert ksp.stock_status(payload) is AvailabilityStatus.UNKNOWN


def test_stock_never_throws_on_weird_branch():
    j = {"result": {"stores": {"a": None, "b": {"qnt": [1, 2]}}}}
    assert ksp.stock_status(j) is AvailabilityStatus.UNKNOWN


def test_stock_rejects_bool_qnt():
    # A boolean qnt isn't a stock count — must not read as in-stock (int(True) == 1).
    assert ksp.stock_status(_stores([True])) is AvailabilityStatus.UNKNOWN


# --- DTO move did not break `from main import ...` --------------------------------------------
def test_dtos_still_importable_from_main():
    import main
    import models

    for name in (
        "AvailabilityStatus",
        "ExtractionSource",
        "PriceData",
        "ScrapeRequest",
        "ScrapeResponse",
        "ShopNameProposal",
    ):
        assert getattr(main, name) is getattr(models, name)


# ============================== Integration tests (Playwright) ===============================
# These drive the real async orchestration: attach_sse_capture's listener, the queue drain in
# extract, and _fetch_stock's click -> request -> response, all against page.route mocks.
_ITEM_URL = "https://ksp.co.il/web/item/415448"
_UIN = "415448"
_SSE_OK = _item_sse(int(_UIN), 349)
_MLAY_INSTOCK = '{"result": {"stores": {"1": {"qnt": 0}, "2": {"qnt": 4}}}}'


@pytest.fixture(autouse=True)
def _fast_ksp_timeouts(monkeypatch):
    # Shrink the handler's waits so stock-miss / beyond-cap tests finish in ~1s, not seconds.
    monkeypatch.setattr(ksp, "_PRICE_DEADLINE_S", 1.0)
    monkeypatch.setattr(ksp, "_STOCK_TIMEOUT_MS", 800)
    monkeypatch.setattr(ksp, "_CLICK_RETRY_WAIT_MS", 200)


@pytest_asyncio.fixture
async def page():
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True, args=["--no-sandbox"])
        try:
            ctx = await browser.new_context()
            yield await ctx.new_page()
        finally:
            await browser.close()


def _html(*, button=True, wire_button=True, hidden_dup=False, fire_sse=True):
    hidden = '<button style="display:none">לבדיקת מלאי</button>' if hidden_dup else ""
    btn = '<button id="stk">לבדיקת מלאי בסניפים</button>' if button else ""
    sse_js = "fetch('/m_action/sse/streams', {method: 'POST'});" if fire_sse else ""
    wire_js = (
        "document.getElementById('stk').addEventListener('click', "
        "() => fetch('/m_action/api/mlay/362345'));"
        if button and wire_button
        else ""
    )
    return f"<html><body>{hidden}{btn}<script>{sse_js}{wire_js}</script></body></html>"


def _make_handler(html, sse_body, mlay_body, sse_delay, redirect_to):
    async def handler(route):
        url = route.request.url
        if "/m_action/sse/streams" in url:
            if sse_body is None:
                await route.abort()
            else:
                if sse_delay:
                    await asyncio.sleep(sse_delay)
                await route.fulfill(
                    status=200, content_type="text/event-stream; charset=utf-8", body=sse_body
                )
        elif "/m_action/api/mlay/" in url:
            if mlay_body is None:
                await route.abort()
            else:
                await route.fulfill(
                    status=200, content_type="application/json; charset=utf-8", body=mlay_body
                )
        elif route.request.resource_type == "document":
            # A client-side redirect (not route.fulfill(302), whose fulfilled target doesn't run
            # inline scripts under Playwright) so the item page's SSE-firing script actually runs.
            if redirect_to and url != redirect_to:
                body = (
                    f"<html><body><script>location.replace({redirect_to!r})</script></body></html>"
                )
                await route.fulfill(status=200, content_type="text/html; charset=utf-8", body=body)
            else:
                # charset=utf-8 is required or the browser mis-decodes the Hebrew button text
                # and get_by_text can't match it (real KSP serves utf-8).
                await route.fulfill(status=200, content_type="text/html; charset=utf-8", body=html)
        else:
            await route.fulfill(status=200, body="")

    return handler


async def _run_extract(
    page,
    *,
    url=_ITEM_URL,
    html=None,
    sse_body=_SSE_OK,
    mlay_body=_MLAY_INSTOCK,
    sse_delay=0.0,
    redirect_to=None,
):
    handler = _make_handler(html or _html(), sse_body, mlay_body, sse_delay, redirect_to)
    await page.route("**/*", handler)
    cap = ksp.attach_sse_capture(page)  # before goto, per the intercept-not-replay rule
    await page.goto(url, wait_until="domcontentloaded")
    if redirect_to:
        # The client-side redirect navigates after goto resolves; wait for the item page to land
        # (a real KSP HTTP 302 would be followed inside goto, but route.fulfill can't run target
        # scripts — see the handler comment).
        await page.wait_for_url(redirect_to, wait_until="domcontentloaded")
    return await ksp.extract(page, cap)


async def test_extract_price_and_stock_happy(page):
    result = await _run_extract(page)
    assert result is not None
    assert result.extractionSource is ExtractionSource.STRUCTURED
    assert result.priceData.price == 349.0
    assert result.priceData.currency == "ILS"
    assert result.priceData.availability is AvailabilityStatus.AVAILABLE
    assert result.shopNameProposal.name == "KSP"
    assert result.shopNameProposal.strong is True


async def test_extract_none_when_uin_absent(page):
    # The page's item.item payload is for a different uin -> our uin not found -> None.
    sse = _item_sse(999, 50)
    assert await _run_extract(page, sse_body=sse) is None


async def test_extract_stock_unknown_when_mlay_never_fires(page):
    # Button present but not wired -> click fires no mlay -> stock degrades to UNKNOWN.
    result = await _run_extract(page, html=_html(wire_button=False))
    assert result.priceData.price == 349.0
    assert result.priceData.availability is AvailabilityStatus.UNKNOWN


async def test_extract_stock_unknown_when_button_missing(page):
    result = await _run_extract(page, html=_html(button=False))
    assert result.priceData.price == 349.0
    assert result.priceData.availability is AvailabilityStatus.UNKNOWN


async def test_extract_handles_delayed_sse(page):
    # SSE arrives after a delay still within the price deadline -> still captured.
    result = await _run_extract(page, sse_delay=0.3)
    assert result is not None
    assert result.priceData.price == 349.0


async def test_extract_none_when_sse_beyond_cap(page):
    # SSE arrives after the price deadline -> extract returns None (orchestration then falls back).
    assert await _run_extract(page, sse_delay=1.5) is None


async def test_extract_handles_redirect_to_item(page):
    # A non-item KSP URL that 302s to /web/item/<uin>: attach-on-host + uin from final page.url.
    result = await _run_extract(page, url="https://ksp.co.il/go", redirect_to=_ITEM_URL)
    assert result is not None
    assert result.priceData.price == 349.0


async def test_extract_clicks_visible_button(page):
    # A hidden duplicate + the visible wired button: the visible one is clicked -> mlay fires.
    result = await _run_extract(page, html=_html(hidden_dup=True))
    assert result.priceData.availability is AvailabilityStatus.AVAILABLE


async def test_extract_none_on_redirect_to_non_ksp_host(page):
    # A KSP URL that (open-)redirects OFF-SITE must NOT yield a KSP result, even if the off-site
    # page has a /web/item/ path + a fake SSE — extract checks the final host, not just the path.
    result = await _run_extract(
        page, url=_ITEM_URL, redirect_to="https://evil.example/web/item/415448"
    )
    assert result is None


class _RoutedBrowser:
    """Wraps a real Browser so every context scrape() creates is pre-routed with our mocks."""

    def __init__(self, real, handler):
        self._real = real
        self._handler = handler

    def is_connected(self):
        return self._real.is_connected()

    async def new_context(self, **kwargs):
        ctx = await self._real.new_context(**kwargs)
        await ctx.route("**/*", self._handler)
        return ctx


async def test_scrape_dispatches_to_ksp_end_to_end(monkeypatch):
    # Drives the real main.scrape(): exercises the two touchpoints (attach-before-goto +
    # dispatch-after-block-detection) that the extract()-level tests don't reach.
    import main

    handler = _make_handler(_html(), _SSE_OK, _MLAY_INSTOCK, 0.0, None)
    async with async_playwright() as p:
        real = await p.chromium.launch(headless=True, args=["--no-sandbox"])
        monkeypatch.setattr(main, "browser", _RoutedBrowser(real, handler))
        try:
            result = await main.scrape(main.ScrapeRequest(url=_ITEM_URL))
        finally:
            await real.close()

    assert result.extractionSource is ExtractionSource.STRUCTURED
    assert result.priceData.price == 349.0
    assert result.priceData.availability is AvailabilityStatus.AVAILABLE
    assert result.shopNameProposal.name == "KSP"


async def test_scrape_dispatches_to_ksp_after_non_ksp_redirect(monkeypatch):
    # A NON-KSP url (a shortener / affiliate / share link) that redirects INTO a KSP item must
    # still trigger the handler: attach_sse_capture is now UNCONDITIONAL and dispatch keys on the
    # FINAL page.url host. (A real shortener uses a network 302 the browser follows the same way;
    # route.fulfill can't run a 302 target's scripts, so we redirect client-side — equivalent for
    # the page.url the dispatch reads, and the price SSE then fires from the landed KSP page.)
    import main

    handler = _make_handler(_html(), _SSE_OK, _MLAY_INSTOCK, 0.0, _ITEM_URL)
    async with async_playwright() as p:
        real = await p.chromium.launch(headless=True, args=["--no-sandbox"])
        monkeypatch.setattr(main, "browser", _RoutedBrowser(real, handler))
        try:
            result = await main.scrape(main.ScrapeRequest(url="https://kspl.ink/p/abc"))
        finally:
            await real.close()

    assert result.extractionSource is ExtractionSource.STRUCTURED
    assert result.priceData.price == 349.0
    assert result.priceData.availability is AvailabilityStatus.AVAILABLE
    assert result.shopNameProposal.name == "KSP"


async def _scrape_with_ksp_extract(monkeypatch, fake_extract):
    import main

    monkeypatch.setattr(ksp, "extract", fake_extract)
    handler = _make_handler(_html(), _SSE_OK, _MLAY_INSTOCK, 0.0, None)
    async with async_playwright() as p:
        real = await p.chromium.launch(headless=True, args=["--no-sandbox"])
        monkeypatch.setattr(main, "browser", _RoutedBrowser(real, handler))
        try:
            return await main.scrape(main.ScrapeRequest(url=_ITEM_URL))
        finally:
            await real.close()


async def test_scrape_falls_back_to_generic_when_ksp_returns_none(monkeypatch):
    # KSP item page but the handler yields no price -> scrape() falls through to the generic
    # waterfall (not STRUCTURED) rather than returning nothing.
    async def _none(page, queue):
        return None

    result = await _scrape_with_ksp_extract(monkeypatch, _none)
    assert result.extractionSource is not ExtractionSource.STRUCTURED


async def test_scrape_falls_back_to_generic_when_ksp_raises(monkeypatch):
    # An exception in the KSP handler must not crash the scrape — it falls through to generic.
    async def _raise(page, queue):
        raise RuntimeError("boom")

    result = await _scrape_with_ksp_extract(monkeypatch, _raise)
    assert result.extractionSource is not ExtractionSource.STRUCTURED
