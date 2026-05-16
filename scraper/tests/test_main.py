import pytest
import pytest_asyncio
from playwright.async_api import async_playwright

from main import _STRIP_DECOY_PRICES_SCRIPT, _STRUCTURED_DATA_SCRIPT, _extract_snippet


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
