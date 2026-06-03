from pathlib import Path

import pytest_asyncio
from playwright.async_api import async_playwright

from main import (
    _STRIP_DECOY_PRICES_SCRIPT,
    _STRUCTURED_DATA_SCRIPT,
    _detect_block,
    _extract_snippet,
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
    assert result == {"price": 9990, "currency": "ILS", "available": True}


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
    assert result == {"price": 719, "currency": "USD", "available": True}


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
    assert result["available"] is True


# Gemini PR #20 follow-up — `availability` is supposed to be a URI string,
# but some publishers emit a nested ItemAvailability object. Without the
# String() wrap, .toLowerCase() throws TypeError, the outer try/except in
# the JSON-LD loop swallows it, and the whole script block aborts —
# silently demoting the page to Tier 2. The fix keeps extraction alive
# (price still returns); the available flag falls back to false, which is
# a much smaller harm than losing the structured tier entirely.
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
