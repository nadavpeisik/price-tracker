import pytest
import pytest_asyncio
from playwright.async_api import async_playwright

from main import _STRUCTURED_DATA_SCRIPT, _extract_snippet


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
    snippet = await _extract_snippet(page)
    assert snippet is not None
    assert "40.00" in snippet
    assert "50.00" not in snippet
