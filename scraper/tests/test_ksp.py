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


# --- parse_item_from_sse ---------------------------------------------------------------------
def _item_sse(uin, price, sep="\n\n", catalog="362345"):
    """Build a realistic KSP single-item ("item.item") SSE body — the shape verified live.

    `uinsql` (the catalog id) is present by default because it is present on every live payload;
    a fixture that omits what production always sends is how #196 stayed green for seven weeks.
    Pass catalog=None to exercise the missing-catalog path deliberately.
    """
    product = {"uin": uin, "price": price}
    if catalog is not None:
        product["uinsql"] = catalog
    payload = {"key": "item.item", "data": {"result": {"data": product}}}
    return f"data: {json.dumps(payload)}{sep}"


def _price_of(sse_text, uin):
    """The price parse_item_from_sse found, or None — keeps the price-focused cases readable."""
    item = ksp.parse_item_from_sse(sse_text, uin)
    return item.price if item is not None else None


# A realistic 2-event stream: the item.item payload for our product, plus a keyed item.bms
# (related-products) payload we must NOT read from.
_SSE = (
    "event: partial\n"
    + _item_sse(415448, 349)
    + 'data: {"key": "item.bms", "data": {"result": {"349185": {"uin": 349185, "price": 1599}}}}\n'
)


def test_price_hit():
    assert _price_of(_SSE, "415448") == 349.0


def test_price_absent_uin():
    assert _price_of(_SSE, "999") is None


def test_price_ignores_keyed_bms_payload():
    # 349185 appears only in the keyed item.bms (related-products) payload, never item.item; we
    # read only the page's own item, so its price must not be returned.
    assert _price_of(_SSE, "349185") is None


def test_price_ignores_non_item_item_event():
    # An event with the item shape + our uin but a non-"item.item" key must be ignored.
    sse = 'data: {"key": "item.other", "data": {"result": {"data": {"uin": 1, "price": 99}}}}\n\n'
    assert _price_of(sse, "1") is None


def test_price_tolerates_malformed_event():
    sse = "data: not json\n\n" + _item_sse(1, 12)
    assert _price_of(sse, "1") == 12.0


def test_price_crlf_boundaries():
    body = _item_sse(1, 99).replace("\n", "\r\n")
    assert _price_of(body, "1") == 99.0


def test_price_string_with_comma():
    assert _price_of(_item_sse(1, "1,299"), "1") == 1299.0


@pytest.mark.parametrize("price", [0, -5, "abc", None, "0"])
def test_price_rejects_nonpositive_or_nonnumeric(price):
    assert _price_of(_item_sse(1, price), "1") is None


def test_price_tolerates_shape_change():
    # data.result is a list, not a dict — skipped, not raised.
    sse = 'data: {"data": {"result": [1, 2, 3]}}\n'
    assert _price_of(sse, "1") is None


# --- parse_item_from_sse: the catalog id (uinsql) ---------------------------------------------
def _catalog_of(sse_text, uin):
    item = ksp.parse_item_from_sse(sse_text, uin)
    return item.catalog if item is not None else None


def test_catalog_read_from_uinsql():
    # The live pairing: item 415448's branch stock is keyed by catalog 362345, not by the uin.
    assert _catalog_of(_item_sse(415448, 349, catalog="362345"), "415448") == "362345"


def test_catalog_accepts_non_numeric_id():
    # Live item 418392 returns "F000029" — catalog ids are opaque tokens, not integers.
    assert _catalog_of(_item_sse(1, 10, catalog="F000029"), "1") == "F000029"


def test_catalog_accepts_integer_uinsql():
    # KSP sends uinsql as a string today; an int would still be a usable id.
    assert _catalog_of(_item_sse(1, 10, catalog=362345), "1") == "362345"


@pytest.mark.parametrize(
    "catalog",
    [
        None,  # field absent entirely
        "",  # present but empty
        "  ",  # whitespace only
        "../../etc/passwd",  # path traversal must never reach the URL
        "362345/../admin",
        "362345?x=1",
        "a" * 33,  # over the length cap
        True,  # a flag is not an id
        [],  # wrong type
    ],
)
def test_catalog_rejected_leaves_price_intact(catalog):
    # An unusable catalog must cost us availability only — never the price, and never a URL.
    item = ksp.parse_item_from_sse(_item_sse(1, 10, catalog=catalog), "1")
    assert item is not None
    assert item.price == 10.0
    assert item.catalog is None


def test_catalog_strips_surrounding_whitespace():
    assert _catalog_of(_item_sse(1, 10, catalog=" 362345 "), "1") == "362345"


def test_catalog_later_complete_event_beats_earlier_priced_one():
    # Two events for our uin, the first without a catalog. Returning the first would cost
    # availability for nothing — the price is identical (Codex adversarial review).
    sse = _item_sse(1, 10, catalog=None) + _item_sse(1, 10, catalog="362345")
    item = ksp.parse_item_from_sse(sse, "1")
    assert item.price == 10.0
    assert item.catalog == "362345"


def test_catalog_unusable_in_later_event_does_not_override_earlier_price():
    # Neither event has a usable catalog -> the FIRST priced item still wins, as it always did.
    sse = _item_sse(1, 10, catalog=None) + _item_sse(1, 99, catalog="../evil")
    item = ksp.parse_item_from_sse(sse, "1")
    assert item.price == 10.0
    assert item.catalog is None


def test_complete_event_wins_even_when_a_priced_one_precedes_it_for_another_uin():
    # A different uin's complete event must not satisfy our search.
    sse = _item_sse(999, 50, catalog="111111") + _item_sse(1, 10, catalog="362345")
    item = ksp.parse_item_from_sse(sse, "1")
    assert (item.price, item.catalog) == (10.0, "362345")


# --- _origin_of ------------------------------------------------------------------------------
@pytest.mark.parametrize(
    "url,expected",
    [
        ("https://ksp.co.il/web/item/1", "https://ksp.co.il"),
        ("https://KSP.CO.IL/web/item/1", "https://ksp.co.il"),  # browsers lowercase the host
        ("https://ksp.co.il:443/web/item/1", "https://ksp.co.il"),  # default port omitted
        ("http://ksp.co.il:80/x", "http://ksp.co.il"),
        ("https://ksp.co.il:8443/x", "https://ksp.co.il:8443"),  # non-default port kept
        ("https://www.ksp.co.il/x", "https://www.ksp.co.il"),  # subdomain is a distinct origin
    ],
)
def test_origin_of(url, expected):
    # Must spell the origin exactly as the browser's location.origin does — the two are compared
    # for string equality inside ksp_branch_stock.js.
    assert ksp._origin_of(url) == expected


def test_sse_data_blocks_strips_exactly_one_leading_space():
    # SSE spec: remove at most ONE leading space from a data: value (not all whitespace). Two
    # spaces -> one removed, one kept; a tab is preserved entirely.
    assert list(ksp._sse_data_blocks("data:  X\n\n")) == [" X"]
    assert list(ksp._sse_data_blocks("data:\tX\n\n")) == ["\tX"]


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
# extract, and _fetch_stock's in-page fetch, all against page.route mocks.
_ITEM_URL = "https://ksp.co.il/web/item/415448"
_UIN = "415448"
_CATALOG = "362345"
_SSE_OK = _item_sse(int(_UIN), 349, catalog=_CATALOG)
_MLAY_INSTOCK = '{"result": {"stores": {"1": {"qnt": 0}, "2": {"qnt": 4}}}}'
_MLAY_SOLD_OUT = '{"result": {"stores": {"1": {"qnt": 0}, "2": {"qnt": 0}}}}'


@pytest.fixture(autouse=True)
def _fast_ksp_timeouts(monkeypatch):
    # Shrink the handler's waits so stock-miss / beyond-cap tests finish in ~1s, not seconds.
    monkeypatch.setattr(ksp, "_PRICE_DEADLINE_S", 1.0)
    monkeypatch.setattr(ksp, "_STOCK_TIMEOUT_MS", 800)


@pytest_asyncio.fixture
async def page():
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True, args=["--no-sandbox"])
        try:
            ctx = await browser.new_context()
            # Stand in for Cloudflare's clearance cookie. _make_handler refuses an mlay request
            # that arrives without it, which is what gives these tests any grip on the property
            # the whole approach rests on: that the request is document-initiated and credentialed.
            # Without this a regression to `credentials: 'omit'` would keep every test green while
            # production 403'd straight back to UNKNOWN (Codex adversarial review, #196).
            await ctx.add_cookies(
                [{"name": "cf_clearance", "value": "test-token", "url": "https://ksp.co.il"}]
            )
            yield await ctx.new_page()
        finally:
            await browser.close()


def _html(*, fire_sse=True, stock_button=False):
    """The item page. It has NO stock button by default — that is the point of #196: availability
    must not depend on any DOM affordance. `stock_button=True` adds one (wired to nothing) purely
    to prove its presence or absence changes nothing.
    """
    btn = "<button>בדיקת מלאי בסניפים</button>" if stock_button else ""
    sse_js = "fetch('/m_action/sse/streams', {method: 'POST'});" if fire_sse else ""
    return f"<html><body>{btn}<script>{sse_js}</script></body></html>"


def _make_handler(
    html, sse_body, mlay_body, sse_delay, redirect_to, mlay_status=200, mlay_reqs=None
):
    mlay_reqs = mlay_reqs if mlay_reqs is not None else []

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
            headers = await route.request.all_headers()
            mlay_reqs.append({"url": url, "headers": headers})
            # Stand in for the bot wall: no clearance cookie, no data. A fetch that stopped
            # sending credentials would 403 here exactly as it does in production, instead of
            # being waved through by a mock that only looks at the URL.
            if "cf_clearance" not in headers.get("cookie", ""):
                await route.fulfill(
                    status=403, content_type="text/html; charset=utf-8", body="<html>403</html>"
                )
            elif mlay_body is None:
                await route.abort()
            else:
                await route.fulfill(
                    status=mlay_status,
                    content_type="application/json; charset=utf-8",
                    body=mlay_body,
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
                # charset=utf-8 is required or the browser mis-decodes the Hebrew text (real KSP
                # serves utf-8).
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
    mlay_status=200,
    sse_delay=0.0,
    redirect_to=None,
    mlay_reqs=None,
):
    handler = _make_handler(
        html or _html(), sse_body, mlay_body, sse_delay, redirect_to, mlay_status, mlay_reqs
    )
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


async def test_extract_resolves_stock_with_no_button_on_the_page(page):
    # The #196 regression, pinned: the default fixture page has no stock button at all, and stock
    # still resolves. The old click path returned UNKNOWN for exactly this page.
    result = await _run_extract(page, html=_html(stock_button=False))
    assert result.priceData.availability is AvailabilityStatus.AVAILABLE


async def test_extract_reports_unavailable_when_every_branch_is_empty(page):
    # The case the click path could never reach: a sold-out item renders no stock button, so it
    # stayed UNKNOWN forever. Asking the endpoint directly gets a real UNAVAILABLE.
    result = await _run_extract(page, html=_html(stock_button=False), mlay_body=_MLAY_SOLD_OUT)
    assert result.priceData.price == 349.0
    assert result.priceData.availability is AvailabilityStatus.UNAVAILABLE


async def test_extract_stock_lookup_is_same_origin_and_keyed_by_catalog(page):
    reqs = []
    await _run_extract(page, mlay_reqs=reqs)
    assert [r["url"] for r in reqs] == [f"https://ksp.co.il/m_action/api/mlay/{_CATALOG}"]


async def test_extract_stock_request_is_document_initiated_and_credentialed(page):
    # The reason this approach works at all is that the request goes out from the page, carrying
    # the clearance cookie and the same-origin fetch metadata a bare replay cannot produce. Assert
    # those properties directly — a mock that only matched the URL would stay green through a
    # regression to `credentials: 'omit'`, which is 403 in production (Codex adversarial review).
    reqs = []
    result = await _run_extract(page, mlay_reqs=reqs)
    assert result.priceData.availability is AvailabilityStatus.AVAILABLE
    headers = reqs[0]["headers"]
    # The clearance cookie proves it went out credentialed; the referer proves it was initiated by
    # the document rather than replayed out-of-band. (sec-fetch-site is deliberately not asserted:
    # Chromium does not expose it on a route-intercepted request, so an assertion on it would be
    # testing Playwright, not us.)
    assert "cf_clearance" in headers.get("cookie", "")
    assert headers.get("referer", "").startswith("https://ksp.co.il/")


async def test_fetch_stock_refuses_after_the_page_navigates_off_ksp(page):
    # The window the origin check exists for: extract() validates page.url, then spends up to
    # _PRICE_DEADLINE_S draining the SSE queue. A page that navigates off-site inside that window
    # would resolve the handler's RELATIVE stock path against the attacker's origin, sending a
    # credentialed request there and accepting its stock JSON as KSP's.
    #
    # Driven against _fetch_stock directly rather than racing extract() against a timed redirect:
    # the guard is what is under test, and a timing-dependent version of this test would be the
    # kind of test that passes for the wrong reason.
    reqs = []
    handler = _make_handler(_html(), _SSE_OK, _MLAY_INSTOCK, 0.0, None, mlay_reqs=reqs)
    await page.route("**/*", handler)
    await page.goto(_ITEM_URL, wait_until="domcontentloaded")

    # Sanity: on the validated origin the lookup succeeds, so a later UNKNOWN can only come from
    # the origin check and not from a broken fixture.
    assert (
        await ksp._fetch_stock(page, _CATALOG, "https://ksp.co.il") is AvailabilityStatus.AVAILABLE
    )

    await page.goto("https://evil.example/web/item/415448", wait_until="domcontentloaded")
    reqs.clear()
    assert await ksp._fetch_stock(page, _CATALOG, "https://ksp.co.il") is AvailabilityStatus.UNKNOWN
    assert reqs == []  # and no request was made to ANY origin


async def test_extract_none_when_uin_absent(page):
    # The page's item.item payload is for a different uin -> our uin not found -> None.
    sse = _item_sse(999, 50)
    assert await _run_extract(page, sse_body=sse) is None


async def test_extract_stock_unknown_when_catalog_missing(page):
    # No uinsql in the payload -> nothing to look stock up by -> UNKNOWN, and no request at all
    # (we must not guess a catalog id from the uin: they are different numbers).
    reqs = []
    result = await _run_extract(
        page, sse_body=_item_sse(int(_UIN), 349, catalog=None), mlay_reqs=reqs
    )
    assert result.priceData.price == 349.0
    assert result.priceData.availability is AvailabilityStatus.UNKNOWN
    assert reqs == []


async def test_extract_stock_unknown_on_non_2xx(page):
    result = await _run_extract(page, mlay_status=503, mlay_body='{"error": "nope"}')
    assert result.priceData.price == 349.0
    assert result.priceData.availability is AvailabilityStatus.UNKNOWN


async def test_extract_stock_unknown_when_mlay_aborted(page):
    # The request is aborted -> the in-page fetch rejects -> UNKNOWN (not a crash).
    result = await _run_extract(page, mlay_body=None)
    assert result.priceData.price == 349.0
    assert result.priceData.availability is AvailabilityStatus.UNKNOWN


async def test_extract_stock_unknown_when_mlay_body_is_not_json(page):
    # A bot-wall HTML interstitial served with a 200 -> res.json() throws -> UNKNOWN.
    result = await _run_extract(page, mlay_body="<html>Just a moment...</html>")
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
        # Same clearance cookie the `page` fixture seeds — _make_handler 403s an mlay request
        # without it, so these end-to-end cases need it too.
        await ctx.add_cookies(
            [{"name": "cf_clearance", "value": "test-token", "url": "https://ksp.co.il"}]
        )
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
