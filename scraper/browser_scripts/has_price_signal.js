// @ts-check
/** @returns {boolean} */
() => {
    // Tier 1 signal: a JSON-LD block that actually carries price/offer data.
    for (const s of document.querySelectorAll('script[type="application/ld+json"]'))
        if (/"(price|offers|Offer)"/.test(s.textContent || '')) return true;
    // Structured microdata/meta: a valid signal even when not visually rendered.
    if (document.querySelector('[itemprop="price"], meta[property="product:price:amount"]'))
        return true;
    // Tier 2 heuristic: a [class*="price"] element, but only if it's visible AND holds a
    // digit. Visibility skips hidden skeletons; the digit check skips *visible* empty
    // skeletons/placeholders (e.g. <div class="price-skeleton">) that render before the
    // price data is fetched — both would otherwise trip the gate before the price exists.
    for (const el of document.querySelectorAll('[class*="price"]'))
        if (el.checkVisibility && el.checkVisibility() && /[0-9]/.test(el.textContent || '')) return true;
    return false;
}
