import time
from pathlib import Path

import pytest
import pytest_asyncio
from fastapi import HTTPException
from playwright.async_api import async_playwright

import main
from main import (
    _HAS_PRICE_SIGNAL_SCRIPT,
    _HIDE_CHROME_SCRIPT,
    _SITE_NAME_SCRIPT,
    _STRIP_DECOY_PRICES_SCRIPT,
    _STRUCTURED_DATA_SCRIPT,
    _VISIBLE_TEXT_LEN_SCRIPT,
    ScrapeRequest,
    ShopNameProposal,
    _detect_block,
    _extract_site_name,
    _extract_snippet,
    _wait_for_render,
    scrape,
)

_FIXTURES = Path(__file__).parent / "fixtures"


class _FakeResponse:
    """Minimal stand-in for Playwright's Response — covers the .status / .headers
    surface that _detect_block reads. Avoids the cost (and flakiness) of a real
    network round-trip in unit tests."""

    def __init__(self, status: int, headers: dict[str, str] | None = None):
        self.status = status
        self.headers = headers or {}


@pytest_asyncio.fixture
async def page():
    async with async_playwright() as p:
        browser = await p.chromium.launch(headless=True, args=["--no-sandbox"])
        try:
            ctx = await browser.new_context()
            pg = await ctx.new_page()
            yield pg
        finally:
            await browser.close()


# Tier 1 — JSON-LD priceSpecification[] with ListPrice label (string6/WooCommerce shape).
# Active price lives only in priceSpecification; the ListPrice entry is MSRP.
async def test_jsonld_priceSpecification_picks_sale_price(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@context":"https://schema.org","@type":"Product","name":"Test",
     "offers":{"@type":"Offer","priceSpecification":[
        {"@type":"UnitPriceSpecification","price":"11990","priceCurrency":"ILS","priceType":"https://schema.org/ListPrice"},
        {"@type":"UnitPriceSpecification","price":"9990","priceCurrency":"ILS"}
     ],"availability":"http://schema.org/InStock"}}
    </script></head><body><p>placeholder</p></body></html>
    """
    await page.set_content(html)
    result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
    assert result == {"price": 9990, "currency": "ILS", "availability": "available"}


# Tier 1 — JSON-LD priceSpecification with every entry tagged ListPrice (broken
# publisher). Fall back to overall-lowest so we still return *a* price.
async def test_jsonld_priceSpecification_all_msrp_falls_back_to_lowest(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@type":"Product","offers":{"@type":"Offer","priceSpecification":[
        {"@type":"UnitPriceSpecification","price":"100","priceCurrency":"USD","priceType":"ListPrice"},
        {"@type":"UnitPriceSpecification","price":"80","priceCurrency":"USD","priceType":"https://schema.org/ListPrice"}
    ]}}
    </script></head><body></body></html>
    """
    await page.set_content(html)
    result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
    assert result["price"] == 80
    assert result["currency"] == "USD"


# Tier 1 — Microdata fallback (Thomann shape). No JSON-LD; price/currency in
# meta tags inside an itemtype=Offer container.
async def test_microdata_offer(page):
    html = """
    <html><body>
    <div itemprop="offers" itemscope itemtype="https://schema.org/Offer">
        <meta itemprop="price" content="719">
        <meta itemprop="priceCurrency" content="USD">
        <link itemprop="availability" href="https://schema.org/InStock">
    </div>
    </body></html>
    """
    await page.set_content(html)
    result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
    assert result == {"price": 719, "currency": "USD", "availability": "available"}


# Tier 1 — JSON-LD wins over Microdata when both are present.
async def test_jsonld_wins_over_microdata(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@type":"Offer","price":"42","priceCurrency":"EUR","availability":"InStock"}
    </script></head>
    <body>
    <div itemprop="offers" itemscope itemtype="https://schema.org/Offer">
        <meta itemprop="price" content="999">
        <meta itemprop="priceCurrency" content="USD">
    </div>
    </body></html>
    """
    await page.set_content(html)
    result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
    assert result["price"] == 42
    assert result["currency"] == "EUR"


# Tier 2 — WooCommerce <del> markup. Without stripping, inner_text flattens both
# prices and the LLM picks the wrong one.
async def test_snippet_strips_del_tag(page):
    html = """
    <html><body>
    <div class="product-price">
        <del><span class="amount">11,990</span></del>
        <span class="amount">9,990</span>
    </div>
    <p class="stock">This product is currently in stock</p>
    </body></html>
    """
    await page.set_content(html)
    await page.evaluate(_STRIP_DECOY_PRICES_SCRIPT)
    snippet = await _extract_snippet(page)
    assert snippet is not None
    assert "9,990" in snippet
    assert "11,990" not in snippet


# Tier 2 — Shopify/Squarespace <s> tag, same role as <del>.
async def test_snippet_strips_s_tag(page):
    html = """
    <html><body>
    <div class="price">
        <s>$99.00</s>
        <span>$79.00</span>
    </div>
    </body></html>
    """
    await page.set_content(html)
    await page.evaluate(_STRIP_DECOY_PRICES_SCRIPT)
    snippet = await _extract_snippet(page)
    assert snippet is not None
    assert "79.00" in snippet
    assert "99.00" not in snippet


# Tier 2 — class-based strikethrough (no semantic tag, just CSS class).
async def test_snippet_strips_strikethrough_class(page):
    html = """
    <html><body>
    <div class="price">
        <span class="regular-price">$50.00</span>
        <span class="sale-price">$40.00</span>
    </div>
    </body></html>
    """
    await page.set_content(html)
    await page.evaluate(_STRIP_DECOY_PRICES_SCRIPT)
    snippet = await _extract_snippet(page)
    assert snippet is not None
    assert "40.00" in snippet
    assert "50.00" not in snippet


# Gemini PR #20 comment 1 — microdata with <del> inside itemprop="price".
# readProp used to fall through to innerText and concatenate "99.00" + "79.00".
# The global pre-Tier-1 strip removes the <del> before microdata reads anything.
async def test_microdata_strips_del_inside_price(page):
    html = """
    <html><body>
    <div itemprop="offers" itemscope itemtype="https://schema.org/Offer">
      <span itemprop="price"><del>99.00</del>79.00</span>
      <meta itemprop="priceCurrency" content="USD">
    </div>
    </body></html>
    """
    await page.set_content(html)
    await page.evaluate(_STRIP_DECOY_PRICES_SCRIPT)
    result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
    assert result["price"] == 79
    assert result["currency"] == "USD"


# No-sale page: .regular-price is the only price. A naive global strip would
# orphan the page. Confirms the sale-pairing guard keeps it intact.
async def test_regular_price_alone_survives_strip(page):
    html = """
    <html><body>
    <div class="price">
      <span class="regular-price">$50.00</span>
    </div>
    </body></html>
    """
    await page.set_content(html)
    await page.evaluate(_STRIP_DECOY_PRICES_SCRIPT)
    snippet = await _extract_snippet(page)
    assert snippet is not None
    assert "50.00" in snippet


# Multi-product safety: card A has paired regular+sale, card B has regular only.
# The strip must remove A's regular AND leave B's regular intact.
async def test_multi_product_strip_is_card_scoped(page):
    html = """
    <html><body>
    <div class="product-card">
      <div class="price">
        <span class="regular-price">$100</span>
        <span class="sale-price">$80</span>
      </div>
    </div>
    <div class="product-card">
      <div class="price">
        <span class="regular-price">$50</span>
      </div>
    </div>
    </body></html>
    """
    await page.set_content(html)
    await page.evaluate(_STRIP_DECOY_PRICES_SCRIPT)
    remaining = await page.evaluate("""
        () => Array.from(document.querySelectorAll('[class*="regular-price"]'))
            .map(n => n.textContent.trim())
    """)
    assert "$100" not in remaining
    assert "$50" in remaining


# Magento-style nested wrappers. parentElement / closest() would fail here;
# the depth-bounded ascent finds the price-box and strips the regular-price.
async def test_strip_handles_wrapped_pair(page):
    html = """
    <html><body>
    <div class="price-box">
      <div class="old-price-wrapper">
        <span class="regular-price">$120</span>
      </div>
      <div class="special-price-wrapper">
        <span class="sale-price">$99</span>
      </div>
    </div>
    </body></html>
    """
    await page.set_content(html)
    await page.evaluate(_STRIP_DECOY_PRICES_SCRIPT)
    remaining = await page.evaluate("""
        () => Array.from(document.querySelectorAll('[class*="regular-price"]'))
            .map(n => n.textContent.trim())
    """)
    assert remaining == []


# Grid-poisoning guard: a card whose sale-price has no paired regular-price
# must not climb past the card boundary and wipe a sibling card's regular-price.
# The firewall (next parent has "grid" in className) breaks the ascent.
async def test_strip_does_not_cross_grid_boundary(page):
    html = """
    <html><body>
    <div class="related-products-grid">
      <div class="product-card">
        <div class="sale-banner">
          <span class="sale-price">$99</span>
        </div>
      </div>
      <div class="product-card">
        <div class="price-box">
          <span class="regular-price">$50</span>
        </div>
      </div>
    </div>
    </body></html>
    """
    await page.set_content(html)
    await page.evaluate(_STRIP_DECOY_PRICES_SCRIPT)
    remaining = await page.evaluate("""
        () => Array.from(document.querySelectorAll('[class*="regular-price"]'))
            .map(n => n.textContent.trim())
    """)
    assert "$50" in remaining


# Legacy table-based product grid. Without 'table' in the firewall, the
# ascent climbs past the <td> into the shared <tr> and wipes the sibling
# cell's regular-price. With it, the ascent halts at the row boundary.
async def test_strip_does_not_cross_table_boundary(page):
    html = """
    <html><body>
    <table class="product-table"><tr>
      <td>
        <div class="sale-banner"><span class="sale-price">$99</span></div>
      </td>
      <td>
        <div class="price-box"><span class="regular-price">$50</span></div>
      </td>
    </tr></table>
    </body></html>
    """
    await page.set_content(html)
    await page.evaluate(_STRIP_DECOY_PRICES_SCRIPT)
    remaining = await page.evaluate("""
        () => Array.from(document.querySelectorAll('[class*="regular-price"]'))
            .map(n => n.textContent.trim())
    """)
    assert "$50" in remaining


# Gemini PR #20 follow-up — European thousands without a decimal. Old
# parseNumeric treated the last dot as the decimal point and returned
# 1234.567 instead of 1234567 for "1.234.567". Multi-separator detection
# (split(sep).length > 2) corrects this.
async def test_jsonld_european_thousands_without_decimal(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@type":"Offer","price":"1.234.567","priceCurrency":"EUR","availability":"InStock"}
    </script></head><body></body></html>
    """
    await page.set_content(html)
    result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
    assert result["price"] == 1234567
    assert result["currency"] == "EUR"


# Gemini PR #20 follow-up — single-separator + exactly 3 trailing digits is
# almost always a thousands grouping, not a decimal. Without this heuristic,
# an Israeli/EU publisher emitting "9,990" in JSON-LD would silently parse as
# 9.99 — three orders of magnitude wrong, passes price > 0 validation.
async def test_jsonld_single_separator_three_digits_is_thousands(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@type":"Offer","price":"9,990","priceCurrency":"ILS","availability":"InStock"}
    </script></head><body></body></html>
    """
    await page.set_content(html)
    result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
    assert result["price"] == 9990
    assert result["currency"] == "ILS"


# Gemini PR #20 follow-up — single-separator + 2 trailing digits is the
# canonical decimal case. Heuristic must NOT misclassify "1,23" as thousands.
async def test_jsonld_single_separator_two_digits_is_decimal(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@type":"Offer","price":"1,23","priceCurrency":"EUR","availability":"InStock"}
    </script></head><body></body></html>
    """
    await page.set_content(html)
    result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
    assert result["price"] == 1.23
    assert result["currency"] == "EUR"


# Gemini PR #20 follow-up — priceSpecification[] can mix UnitPriceSpecification
# (the real product price) with DeliveryChargeSpecification (shipping). Without
# a @type allowlist, the min-reduce would pick the lower shipping cost as the
# product price. The PRODUCT_PRICE_TYPES filter keeps shipping out of the pool.
async def test_jsonld_priceSpecification_ignores_shipping_entry(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@type":"Offer","priceCurrency":"USD","priceSpecification":[
        {"@type":"UnitPriceSpecification","price":"49.99","priceCurrency":"USD"},
        {"@type":"DeliveryChargeSpecification","price":"5.99","priceCurrency":"USD"}
    ]}
    </script></head><body></body></html>
    """
    await page.set_content(html)
    result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
    assert result["price"] == 49.99
    assert result["currency"] == "USD"


# Gemini PR #20 follow-up — strikethrough selector tightened from [class*=] to
# [class~=] so we don't strip `.not-strikethrough` opt-outs. Verify the exact
# token still catches the real case.
async def test_strip_exact_token_strikethrough_class(page):
    html = """
    <html><body>
    <div class="product-price">
      <span class="strikethrough">100.00</span>
      <span class="amount">79.00</span>
    </div>
    </body></html>
    """
    await page.set_content(html)
    await page.evaluate(_STRIP_DECOY_PRICES_SCRIPT)
    snippet = await _extract_snippet(page)
    assert snippet is not None
    assert "79.00" in snippet
    assert "100.00" not in snippet


# Gemini PR #20 follow-up — `not-strikethrough` is an opt-out class some
# templates use to disable a parent's strikethrough styling. The substring
# selector used to wipe it (and any prices it contained); the [class~=]
# selector lets it survive.
async def test_strip_preserves_not_strikethrough_class(page):
    html = """
    <html><body>
    <div class="product-price">
      <span class="not-strikethrough">79.00</span>
    </div>
    </body></html>
    """
    await page.set_content(html)
    await page.evaluate(_STRIP_DECOY_PRICES_SCRIPT)
    snippet = await _extract_snippet(page)
    assert snippet is not None
    assert "79.00" in snippet


# Gemini PR #20 follow-up — Schema.org PreOrder availability means "purchasable
# but not in stock yet"; treat as available alongside InStock/PreSale.
async def test_jsonld_preorder_marked_available(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@type":"Offer","price":"42","priceCurrency":"USD","availability":"https://schema.org/PreOrder"}
    </script></head><body></body></html>
    """
    await page.set_content(html)
    result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
    assert result["availability"] == "available"


# Tri-state availability (issue #124): the structured tier maps the schema.org URI to one of
# available / unavailable / unknown — absent availability is UNKNOWN, never a fabricated boolean.
async def test_jsonld_outofstock_marked_unavailable(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@type":"Offer","price":"42","priceCurrency":"USD","availability":"https://schema.org/OutOfStock"}
    </script></head><body></body></html>
    """
    await page.set_content(html)
    result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
    assert result["availability"] == "unavailable"


async def test_jsonld_absent_availability_is_unknown(page):
    # No availability field at all → UNKNOWN (the bug fix: it used to default to "available=false").
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@type":"Offer","price":"42","priceCurrency":"USD"}
    </script></head><body></body></html>
    """
    await page.set_content(html)
    result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
    assert result["availability"] == "unknown"


async def test_jsonld_instoreonly_marked_available(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@type":"Offer","price":"42","priceCurrency":"USD","availability":"https://schema.org/InStoreOnly"}
    </script></head><body></body></html>
    """
    await page.set_content(html)
    result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
    assert result["availability"] == "available"


async def test_jsonld_availability_object_shape_normalized(page):
    # Some publishers emit availability as an object ({"@id": ".../InStock"}) rather than a string;
    # the normalizer must read @id (guarded) and still resolve it, not crash or fall to unknown.
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@type":"Offer","price":"42","priceCurrency":"USD","availability":{"@id":"https://schema.org/InStock"}}
    </script></head><body></body></html>
    """
    await page.set_content(html)
    result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
    assert result["availability"] == "available"


# Gemini PR #20 follow-up — `availability` is supposed to be a URI string,
# but some publishers emit a nested ItemAvailability object. Without the
# String() wrap, .toLowerCase() throws TypeError, the outer try/except in
# the JSON-LD loop swallows it, and the whole script block aborts —
# silently demoting the page to Tier 2. The fix keeps extraction alive AND —
# now that the normalizer reads @id/url off the object — resolves availability
# correctly rather than losing the structured tier or defaulting to unknown.
async def test_jsonld_availability_as_object_does_not_crash(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@type":"Offer","price":"42","priceCurrency":"EUR",
     "availability":{"@type":"ItemAvailability","url":"https://schema.org/InStock"}}
    </script></head><body></body></html>
    """
    await page.set_content(html)
    result = await page.evaluate(_STRUCTURED_DATA_SCRIPT)
    assert result is not None
    assert result["price"] == 42
    assert result["currency"] == "EUR"
    assert result["availability"] == "available"


# Gemini PR #20 follow-up — global strikethrough strip must preserve
# non-numeric badges like <s>Sold Out</s>, which Tier 2 needs for the
# availability signal.
async def test_strip_preserves_non_numeric_strikethrough(page):
    html = """
    <html><body>
    <div class="product-price">
        <span class="amount">79.00</span>
    </div>
    <p class="stock"><s>Sold Out</s> back in stock soon</p>
    </body></html>
    """
    await page.set_content(html)
    await page.evaluate(_STRIP_DECOY_PRICES_SCRIPT)
    snippet = await _extract_snippet(page)
    assert snippet is not None
    assert "Sold Out" in snippet
    assert "79.00" in snippet


# Bot-wall detection — saved Cloudflare managed-challenge fixture. Confirms both
# the title check and the _cf_chl_opt presence check fire, and that the reason
# carries the cf-ray from the response headers when available.
async def test_detect_cloudflare_challenge_html(page):
    html = (_FIXTURES / "cloudflare_challenge.html").read_text()
    await page.set_content(html)
    response = _FakeResponse(
        status=403,
        headers={
            "cf-mitigated": "challenge",
            "cf-ray": "9fcfc0abcd123456-TLV",
        },
    )
    blocked, reason = await _detect_block(page, response)
    assert blocked is True
    assert reason is not None
    assert "9fcfc0abcd123456-TLV" in reason


# Bot-wall detection — saved AWS WAF Bot Control challenge fixture. Status 202
# + gokuProps + awsWafCookieDomainList is AWS-specific; verified against real
# normal-page captures (thomann, google, Amazon-when-WAF-off) that none of those
# markers appear. The per-request encrypted `key` blob in the fixture has been
# scrubbed since it doesn't affect detection.
async def test_detect_aws_waf_challenge_html(page):
    html = (_FIXTURES / "aws_waf_challenge.html").read_text()
    await page.set_content(html)
    response = _FakeResponse(status=202, headers={})
    blocked, reason = await _detect_block(page, response)
    assert blocked is True
    assert reason is not None
    assert reason.startswith("aws-waf-challenge")


# Bot-wall detection — normal product page must not false-positive. No CF
# headers, no challenge title, no _cf_chl_opt — _detect_block must return
# (False, None) so we don't BLOCK legitimate pages.
async def test_detect_normal_page_html(page):
    html = """
    <html><head><title>Soldano SLO-30 Classic | Wild Guitars</title></head>
    <body><h1>Soldano SLO-30 Classic</h1><p>Price: $1,999</p></body></html>
    """
    await page.set_content(html)
    response = _FakeResponse(status=200, headers={})
    blocked, reason = await _detect_block(page, response)
    assert blocked is False
    assert reason is None


# Price-signal fast-path — Tier 1 signal. JSON-LD carrying a price means the
# structured tier can succeed, so _wait_for_render can stop immediately.
async def test_price_signal_jsonld(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@context":"https://schema.org","@type":"Product","name":"X",
     "offers":{"@type":"Offer","price":"349","priceCurrency":"ILS"}}
    </script></head><body></body></html>
    """
    await page.set_content(html)
    assert await page.evaluate(_HAS_PRICE_SIGNAL_SCRIPT) is True


# Price-signal fast-path — Tier 2 signal. A rendered [class*="price"] element.
async def test_price_signal_price_element(page):
    await page.set_content('<html><body><span class="product-price">₪349</span></body></html>')
    assert await page.evaluate(_HAS_PRICE_SIGNAL_SCRIPT) is True


# Price-signal fast-path — an unrendered SPA shell has neither signal, so the
# fast-path declines and _wait_for_render falls through to the stabilization wait.
async def test_price_signal_absent_on_shell(page):
    await page.set_content('<html><body><div id="app">KSP</div></body></html>')
    assert await page.evaluate(_HAS_PRICE_SIGNAL_SCRIPT) is False


# Price-signal fast-path — a HIDDEN [class*="price"] (skeleton/template, common in SPA
# shells) must NOT trip the fast-path, or the gate would exit before the real price renders.
async def test_price_signal_ignores_hidden_price_element(page):
    await page.set_content(
        '<html><body><span class="price" style="display:none">₪0</span></body></html>'
    )
    assert await page.evaluate(_HAS_PRICE_SIGNAL_SCRIPT) is False


# Price-signal fast-path — a VISIBLE but digit-less price skeleton/placeholder must NOT trip
# the fast-path; the price data hasn't arrived yet. A real price (with a digit) does.
async def test_price_signal_ignores_visible_empty_skeleton(page):
    await page.set_content('<html><body><div class="price-skeleton"></div></body></html>')
    assert await page.evaluate(_HAS_PRICE_SIGNAL_SCRIPT) is False
    await page.set_content('<html><body><div class="product-price">₪349</div></body></html>')
    assert await page.evaluate(_HAS_PRICE_SIGNAL_SCRIPT) is True


# _HIDE_CHROME_SCRIPT injects a display:none stylesheet for chrome selectors. It must (a) leave
# nodes in the DOM (non-destructive — removing mid-hydration could crash the SPA), (b) drop chrome
# text out of innerText, (c) keep <script> for Tier-1, and crucially (d) be REACTIVE: chrome nodes
# created *after* injection (SPA re-renders) are hidden too, since it's a stylesheet rule not an
# inline style.
async def test_hide_chrome_is_reactive_and_keeps_scripts(page):
    await page.set_content(
        "<html><head>"
        '<script type="application/ld+json">{"@type":"Product"}</script>'
        "</head><body>"
        "<nav>menu</nav><footer>foot</footer>"
        '<div class="cookie-banner">cookies</div>'
        "<main>product text</main>"
        "</body></html>"
    )
    await page.evaluate(_HIDE_CHROME_SCRIPT)
    # Simulate an SPA re-render replacing nav with a fresh node AFTER injection.
    await page.evaluate(
        "() => { document.querySelector('nav').remove();"
        " const n = document.createElement('nav'); n.textContent = 'rerendered menu';"
        " document.body.appendChild(n); }"
    )
    state = await page.evaluate(
        """() => ({
            navStillInDom: document.querySelectorAll('nav').length,
            navVisible: document.querySelector('nav').checkVisibility(),
            footerVisible: document.querySelector('footer').checkVisibility(),
            cookieVisible: document.querySelector('[class*="cookie"]').checkVisibility(),
            mainVisible: document.querySelector('main').checkVisibility(),
            styleInjected: !!document.getElementById('scraper-hide-chrome'),
            scripts: document.querySelectorAll('script[type="application/ld+json"]').length,
            bodyText: document.body.innerText,
        })"""
    )
    assert state["navStillInDom"] == 1  # hidden, not removed
    assert state["navVisible"] is False  # the RE-RENDERED nav is hidden too (reactive)
    assert state["footerVisible"] is False
    assert state["cookieVisible"] is False
    assert state["mainVisible"] is True
    assert state["styleInjected"] is True
    assert state["scripts"] == 1  # Tier-1 JSON-LD survives
    assert "rerendered menu" not in state["bodyText"]
    assert "cookies" not in state["bodyText"]
    assert "product text" in state["bodyText"]


# _wait_for_render — best-effort on a dead page: if the context/page is already closed, the
# browser evals raise and the helper returns instead of propagating (no scrape-killing 500).
async def test_wait_for_render_returns_on_closed_page(page):
    await page.context.close()
    # Must not raise despite every page.evaluate failing on the closed context.
    await _wait_for_render(page, 3000, poll_ms=50)


class _FakeRenderPage:
    """Minimal page stand-in (no browser) for _wait_for_render branch coverage: feeds a
    visible-text-length sequence and can fail wait_for_timeout mid-loop."""

    def __init__(self, lengths, fail_wait=False):
        self._lengths = lengths
        self._i = 0
        self._fail_wait = fail_wait

    async def evaluate(self, script):
        if script is _HAS_PRICE_SIGNAL_SCRIPT:
            return False
        value = self._lengths[min(self._i, len(self._lengths) - 1)]
        self._i += 1
        return value

    async def wait_for_timeout(self, _ms):
        if self._fail_wait:
            raise RuntimeError("target closed")


# _wait_for_render — wait_for_timeout failing mid-loop (page closing during the wait) is
# swallowed and returns best-effort, not propagated past the separate guard.
async def test_wait_for_render_returns_when_wait_for_timeout_raises():
    await _wait_for_render(_FakeRenderPage([100, 100], fail_wait=True), 3000)


# Visible-text length must ignore hidden display:none text. innerText (visible-only),
# not textContent — an SPA shell that ships hidden templates must not inflate the
# length and trip the stability check before the real content renders.
async def test_visible_text_len_ignores_hidden(page):
    hidden = "x" * 300
    await page.set_content(
        f'<html><body><span>abc</span><div style="display:none">{hidden}</div></body></html>'
    )
    assert await page.evaluate(_VISIBLE_TEXT_LEN_SCRIPT) == 3


# _wait_for_render — fast-path. A price signal present at first paint returns well
# under the cap (the escape hatch for pages whose text never settles).
async def test_wait_for_render_returns_on_price_signal(page):
    await page.set_content('<html><body><span class="price">₪349</span></body></html>')
    start = time.monotonic()
    await _wait_for_render(page, 3000, poll_ms=50)
    assert time.monotonic() - start < 1.0


# _wait_for_render — the core SPA case. No price signal; product text is injected
# late (like KSP at ~750ms). Must wait until the text settles, not capture it early.
async def test_wait_for_render_waits_for_late_text(page):
    await page.set_content("<html><body><p>Loading</p></body></html>")
    await page.evaluate(
        """() => setTimeout(() => {
            const d = document.createElement('div');
            d.textContent = 'x'.repeat(300);
            document.body.appendChild(d);
        }, 300)"""
    )
    await _wait_for_render(page, 4000, poll_ms=50, stable_polls=2, min_chars=50)
    assert await page.evaluate(_VISIBLE_TEXT_LEN_SCRIPT) >= 300


# _wait_for_render — never-settling page (text grows forever, no price signal) must
# hit the cap rather than false-settle. Guards the stability logic against runaway DOMs.
async def test_wait_for_render_caps_when_text_never_settles(page):
    await page.set_content("<html><body><p id='c'>start</p></body></html>")
    await page.evaluate(
        """() => { window.__grow = setInterval(() => {
            document.getElementById('c').textContent += ' more-text-chunk';
        }, 30); }"""
    )
    start = time.monotonic()
    await _wait_for_render(page, 800, poll_ms=50)
    elapsed = time.monotonic() - start
    await page.evaluate("() => clearInterval(window.__grow)")
    assert elapsed >= 0.8


# Tier 2 — a shipping/delivery-only page (no price element) must NOT yield a snippet:
# shipping costs aren't the product price, and a price-less snippet would wrongly
# short-circuit the FULLTEXT fallback. (KSP exposes shipping options like "1-6 ימי עסקים
# ₪0" but its price sits in a non-semantic class the snippet selectors can't see.)
async def test_extract_snippet_skips_shipping_only_page(page):
    await page.set_content(
        "<html><body>"
        '<div class="delivery-option">1-6 days ₪0</div>'
        '<div class="delivery-row">courier ₪30</div>'
        "</body></html>"
    )
    assert await _extract_snippet(page) is None


# Tier 2 — with a real price element present, the snippet captures the price and no
# longer drags in shipping-cost text from delivery elements.
async def test_extract_snippet_keeps_price_excludes_shipping(page):
    await page.set_content(
        "<html><body>"
        '<span class="product-price">₪349</span>'
        '<div class="delivery-row">courier ₪30</div>'
        "</body></html>"
    )
    snippet = await _extract_snippet(page)
    assert snippet is not None
    assert "349" in snippet
    assert "courier" not in snippet


# --- lifespan + /scrape availability guard (PR #100) ---


class _FakeBrowser:
    """Minimal Browser stand-in for the /scrape guard: only is_connected() matters,
    and it must be a *method* — `not browser.is_connected` (property) would be a
    permanent no-op, so we assert the called form behaves correctly."""

    def __init__(self, connected: bool):
        self._connected = connected

    def is_connected(self) -> bool:
        return self._connected


# lifespan launches a real Chromium on entry and tears it down on exit. Inside the
# context the global browser is live and connected; after exit the finally has nulled
# it (so a later request hits the 503 guard, and a reused process sees no stale handle).
async def test_lifespan_launches_and_closes_browser():
    assert main.browser is None
    async with main.lifespan(main.app):
        assert main.browser is not None
        assert main.browser.is_connected() is True
    assert main.browser is None


# Pre-startup / post-shutdown window: browser is None → deterministic 503, not an
# opaque NoneType 500 from new_context().
async def test_scrape_returns_503_when_browser_none(monkeypatch):
    monkeypatch.setattr(main, "browser", None)
    with pytest.raises(HTTPException) as exc:
        await scrape(ScrapeRequest(url="https://example.com"))
    assert exc.value.status_code == 503


# Crashed/OOM-killed Chromium: global still points at a Browser but is_connected()
# is False → 503. Guards the `not browser.is_connected()` half of the check.
async def test_scrape_returns_503_when_browser_disconnected(monkeypatch):
    monkeypatch.setattr(main, "browser", _FakeBrowser(connected=False))
    with pytest.raises(HTTPException) as exc:
        await scrape(ScrapeRequest(url="https://example.com"))
    assert exc.value.status_code == 503


# Bad scheme is rejected (400) before the availability guard is even reached.
async def test_scrape_rejects_non_http_scheme(monkeypatch):
    monkeypatch.setattr(main, "browser", _FakeBrowser(connected=True))
    with pytest.raises(HTTPException) as exc:
        await scrape(ScrapeRequest(url="ftp://example.com/file"))
    assert exc.value.status_code == 400


# ─────────────────────────────────────────────────────────────────────────────
# Store-name detection (_SITE_NAME_SCRIPT / _extract_site_name) — issue #33.
# Returns {name, strong} or None. strong=True for site-level signals (og:site_name,
# JSON-LD Organization); the <title> heuristic is weak. See _SITE_NAME_SCRIPT.
# ─────────────────────────────────────────────────────────────────────────────


async def _load_on_host(page, html, url):
    """Serve static HTML as if it came from `url`, so location.hostname is populated —
    set_content alone leaves the page on about:blank with an empty host. The request is
    fulfilled locally; no real network round-trip happens."""

    async def _handler(route):
        await route.fulfill(status=200, content_type="text/html", body=html)

    await page.route("**/*", _handler)
    await page.goto(url, wait_until="domcontentloaded")


# Tier A: og:site_name is the highest-confidence signal — strong, used verbatim.
async def test_sitename_og_site_name_is_strong(page):
    html = """
    <html><head>
    <meta property="og:site_name" content="Musikhaus Thomann">
    <title>RME Babyface Pro FS</title>
    </head><body><p>x</p></body></html>
    """
    await page.set_content(html)
    assert await page.evaluate(_SITE_NAME_SCRIPT) == {"name": "Musikhaus Thomann", "strong": True}


# Tier B: a JSON-LD Organization whose url matches the page host (no og:site_name) — strong.
async def test_sitename_jsonld_organization_is_strong(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@context":"https://schema.org","@type":"Organization","name":"Wild Guitars","url":"https://wildguitars.co.il"}
    </script>
    <title>Soldano SLO-30</title>
    </head><body><p>x</p></body></html>
    """
    await _load_on_host(page, html, "https://www.wildguitars.co.il/product/x")
    assert await page.evaluate(_SITE_NAME_SCRIPT) == {"name": "Wild Guitars", "strong": True}


# Tier B: a list @type and an Organization in @graph are reachable; matching url host → strong.
async def test_sitename_jsonld_type_array_and_graph(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@graph":[
       {"@type":"WebPage","name":"ignored"},
       {"@type":["Organization","Store"],"name":"6th String","url":"https://string6.co.il"}
    ]}
    </script></head><body><p>x</p></body></html>
    """
    await _load_on_host(page, html, "https://www.string6.co.il/product/x")
    assert await page.evaluate(_SITE_NAME_SCRIPT) == {"name": "6th String", "strong": True}


# Tier B guard: a top-level brand/manufacturer Organization on a DIFFERENT host is skipped; the org
# whose url matches the page (the publisher) is used — so a brand can't poison the domain mapping.
async def test_sitename_foreign_brand_org_skipped_publisher_used(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@graph":[
       {"@type":"Organization","name":"Sony","url":"https://www.sony.com"},
       {"@type":"WebSite","name":"GadgetWorld","url":"https://gadgetworld.com"}
    ]}
    </script></head><body><p>x</p></body></html>
    """
    await _load_on_host(page, html, "https://www.gadgetworld.com/dp/1")
    assert await page.evaluate(_SITE_NAME_SCRIPT) == {"name": "GadgetWorld", "strong": True}


# Tier B guard: a lone foreign brand Organization (no matching publisher) is ignored entirely — it
# falls through to the <title> tier (here separator-less → None), never learned as the shop.
async def test_sitename_lone_foreign_brand_org_falls_through(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@type":"Organization","name":"Sony","url":"https://www.sony.com"}
    </script>
    <title>Sony WH-1000XM5</title>
    </head><body><p>x</p></body></html>
    """
    await _load_on_host(page, html, "https://www.gadgetworld.com/dp/1")
    assert await page.evaluate(_SITE_NAME_SCRIPT) is None


# Tier B guard: a protocol-relative brand url (//host) resolves to a foreign host and is skipped —
# without the // handling it would wrongly become a hostless (weak) "Sony".
async def test_sitename_protocol_relative_brand_org_skipped(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@type":"Organization","name":"Sony","url":"//www.sony.com/about"}
    </script>
    <title>Sony WH-1000XM5</title>
    </head><body><p>x</p></body></html>
    """
    await _load_on_host(page, html, "https://www.gadgetworld.com/dp/1")
    assert await page.evaluate(_SITE_NAME_SCRIPT) is None


# Tier B: an Organization with no resolvable host is shown but NOT learnable → weak (strong=false).
async def test_sitename_hostless_org_is_weak(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@context":"https://schema.org","@type":"Organization","name":"GadgetWorld"}
    </script>
    <title>Some Product</title>
    </head><body><p>x</p></body></html>
    """
    await _load_on_host(page, html, "https://www.gadgetworld.com/dp/1")
    assert await page.evaluate(_SITE_NAME_SCRIPT) == {"name": "GadgetWorld", "strong": False}


# Brand-trap: JSON-LD `brand` is never read as the shop (it's the manufacturer). With no other
# signal and a separator-less title, the result is None — never "CERAVE".
async def test_sitename_brand_is_never_picked(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@context":"https://schema.org","@type":"Product","name":"Retinol Serum",
     "brand":{"@type":"Brand","name":"CERAVE"}}
    </script>
    <title>Retinol Serum</title>
    </head><body><p>x</p></body></html>
    """
    await page.set_content(html)
    assert await page.evaluate(_SITE_NAME_SCRIPT) is None


# Marketplace guard: a nested offers.seller Organization must NOT be mistaken for the storefront.
# The script collects top-level/@graph nodes only and never recurses into offers, so the seller
# is invisible to the Organization tier.
async def test_sitename_nested_seller_org_not_picked(page):
    html = """
    <html><head>
    <script type="application/ld+json">
    {"@context":"https://schema.org","@type":"Product","name":"Widget",
     "offers":{"@type":"Offer","price":"9.99",
               "seller":{"@type":"Organization","name":"ACME Third-Party Seller"}}}
    </script>
    <title>Widget</title>
    </head><body><p>x</p></body></html>
    """
    await page.set_content(html)
    assert await page.evaluate(_SITE_NAME_SCRIPT) is None


# Tier C: a title with a separator drops the product segment (matched in og:title); the shop
# segment survives. Weak.
async def test_sitename_title_suffix_with_separator(page):
    html = """
    <html><head>
    <meta property="og:title" content="Cool Product">
    <title>Cool Product | MyShop</title>
    </head><body><p>x</p></body></html>
    """
    await page.set_content(html)
    assert await page.evaluate(_SITE_NAME_SCRIPT) == {"name": "MyShop", "strong": False}


# Tier C: a separator-less title is just the product name and is rejected outright (None).
async def test_sitename_title_without_separator_is_none(page):
    html = "<html><head><title>Just A Product Name</title></head><body><p>x</p></body></html>"
    await page.set_content(html)
    assert await page.evaluate(_SITE_NAME_SCRIPT) is None


# Tier C: a spaced ascii hyphen splits, but a hyphen inside a model number ("SLO-30") does not.
async def test_sitename_title_model_number_hyphen_not_split(page):
    html = """
    <html><head>
    <meta property="og:title" content="Soldano SLO-30 Classic">
    <title>Soldano SLO-30 Classic | Wild Guitars</title>
    </head><body><p>x</p></body></html>
    """
    await page.set_content(html)
    assert await page.evaluate(_SITE_NAME_SCRIPT) == {"name": "Wild Guitars", "strong": False}


# Tier C + bidi: an RTL title wrapped in directional control marks (U+202B … U+202C) still drops
# the brand (matched against og:title) and returns the localized shop name. Regression for
# super-pharm — without the strip, "CERAVE" stays glued to a control char, never matches og:title,
# survives as a second segment, and the heuristic falls through to the host instead.
async def test_sitename_title_strips_bidi_and_drops_brand(page):
    title = chr(0x202B) + "CERAVE - סרום רטינול | סופר-פארם" + chr(0x202C)
    html = f"""
    <html><head>
    <meta property="og:title" content="CERAVE סרום רטינול">
    <title>{title}</title>
    </head><body><p>x</p></body></html>
    """
    await page.set_content(html)
    assert await page.evaluate(_SITE_NAME_SCRIPT) == {"name": "סופר-פארם", "strong": False}


# Tier C: the bare-host segment is dropped, so an "Amazon.com: <product>" title yields no shop
# name (Amazon is resolved via the curated mapping, not detection).
async def test_sitename_host_segment_dropped_returns_none(page):
    html = """
    <html><head>
    <meta property="og:title" content="Cool Product">
    <title>Amazon.com: Cool Product</title>
    </head><body><p>x</p></body></html>
    """
    await _load_on_host(page, html, "https://www.amazon.com/dp/B000")
    assert await page.evaluate(_SITE_NAME_SCRIPT) is None


# One malformed JSON-LD block must not abort the rest (per-script try/catch).
async def test_sitename_malformed_jsonld_block_skipped(page):
    html = """
    <html><head>
    <script type="application/ld+json">{ this is not valid json </script>
    <script type="application/ld+json">
    {"@context":"https://schema.org","@type":"Organization","name":"Recovered Shop","url":"https://recovered.example"}
    </script>
    <title>Product</title>
    </head><body><p>x</p></body></html>
    """
    await _load_on_host(page, html, "https://recovered.example/x")
    assert await page.evaluate(_SITE_NAME_SCRIPT) == {"name": "Recovered Shop", "strong": True}


# No usable signal at all → None.
async def test_sitename_no_signals_returns_none(page):
    await page.set_content("<html><head></head><body><p>x</p></body></html>")
    assert await page.evaluate(_SITE_NAME_SCRIPT) is None


# The Python wrapper returns a ShopNameProposal (name + strong) or None.
async def test_extract_site_name_wrapper(page):
    await page.set_content(
        '<html><head><meta property="og:site_name" content="MyShop">'
        "<title>x</title></head><body><p>x</p></body></html>"
    )
    assert await _extract_site_name(page) == ShopNameProposal(name="MyShop", strong=True)


# The wrapper yields None when nothing is detected.
async def test_extract_site_name_wrapper_none(page):
    await page.set_content("<html><head></head><body><p>x</p></body></html>")
    assert await _extract_site_name(page) is None
