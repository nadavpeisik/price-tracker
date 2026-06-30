// @ts-check
/** @returns {{price:number, currency:string, availability:string}|null} */
() => {
    // MSRP-style labels we want to discard when both list and sale prices are
    // present. Match by suffix so 'https://schema.org/ListPrice' and bare
    // 'ListPrice' both work.
    const MSRP_SUFFIXES = ['ListPrice', 'MSRP', 'SRP', 'RegularPrice', 'StrikethroughPrice'];
    const isMsrpType = (pt) => {
        if (!pt) return false;
        const s = String(pt);
        return MSRP_SUFFIXES.some(suf => s === suf || s.endsWith('/' + suf));
    };

    // Schema.org types that represent the product's unit price. Anything else
    // in priceSpecification[] (DeliveryChargeSpecification, PaymentChargeSpec,
    // etc.) carries shipping/tax/fees, not the item price — must be filtered
    // out before the min-price reduce, or we'd return shipping as the product.
    // Untyped entries pass through (publishers commonly omit @type on the
    // canonical UnitPriceSpecification).
    const PRODUCT_PRICE_TYPES = ['UnitPriceSpecification', 'PriceSpecification', 'CompoundPriceSpecification'];
    const isProductPriceType = (atType) => {
        if (!atType) return true;
        const s = String(atType);
        return PRODUCT_PRICE_TYPES.some(t => s === t || s.endsWith('/' + t));
    };

    const parseNumeric = (raw) => {
        if (raw === undefined || raw === null) return NaN;
        const cleaned = String(raw).replace(/[^0-9.,]/g, '');
        const lastDot = cleaned.lastIndexOf('.');
        const lastComma = cleaned.lastIndexOf(',');
        const sep = lastDot > lastComma ? '.' : (lastComma > lastDot ? ',' : null);
        if (sep === null) {
            return parseFloat(cleaned);
        }
        const sepIdx = cleaned.lastIndexOf(sep);
        const tail = cleaned.substring(sepIdx + 1);
        // Treat as thousands-grouping (strip all separators) when either:
        //  - the separator appears more than once ("1.234.567" — every dot is grouping)
        //  - it appears once with exactly 3 trailing digits ("1,234", "9,990" —
        //    ambiguous in isolation, but in practice almost always a thousands
        //    separator; a decimal with exactly 3 trailing digits is rare outside
        //    of scientific notation, while comma-thousands is common, especially
        //    on ILS/EUR sites that emit prices like "9,990" in JSON-LD).
        if (cleaned.split(sep).length > 2 || tail.length === 3) {
            return parseFloat(cleaned.replace(/[^0-9]/g, ''));
        }
        return parseFloat(
            cleaned.substring(0, sepIdx).replace(/[^0-9]/g, '')
            + '.'
            + tail
        );
    };

    // "can you get it" — orderable states (presale/preorder/backorder/onlineonly) count as available.
    const AVAILABLE_TOKENS = ['instock', 'instoreonly', 'onlineonly', 'limitedavailability', 'presale', 'preorder', 'backorder'];
    const UNAVAILABLE_TOKENS = ['outofstock', 'soldout', 'discontinued'];
    // Normalize a schema.org availability value (full URI, bare token, or {@id}/{url} object) to a
    // bare lowercase alphanumeric token: take the leaf segment (drops the schema.org/ prefix), strip
    // any ?query/#fragment/trailing-slash, then non-alphanumerics ('In Stock'/'in-stock' -> 'instock').
    const normalizeAvailability = (availability) => {
        let raw = availability;
        if (raw && typeof raw === 'object') raw = raw['@id'] || raw.url || '';
        let s = String(raw || '').trim();
        if (!s) return '';
        s = s.split('?')[0].split('#')[0].replace(/\/+$/, '');
        const leaf = s.substring(s.lastIndexOf('/') + 1);
        return leaf.toLowerCase().replace(/[^a-z0-9]/g, '');
    };
    const buildResult = (price, currency, availability) => {
        if (isNaN(price) || price <= 0 || !currency) return null;
        const token = normalizeAvailability(availability);
        let status = 'unknown';
        if (AVAILABLE_TOKENS.includes(token)) status = 'available';
        else if (UNAVAILABLE_TOKENS.includes(token)) status = 'unavailable';
        return {
            price: price,
            currency: currency,
            availability: status
        };
    };

    // Resolve the active price for a JSON-LD Offer. Sites with sales sometimes
    // omit offer.price entirely and put both prices in priceSpecification[] as
    // UnitPriceSpecification entries (one labelled ListPrice for MSRP, one
    // unlabelled for the sale). Filter MSRP-tagged entries out, then pick the
    // lowest of the survivors. Fall back to overall-lowest if every entry is
    // tagged ListPrice (broken publisher) so we still return *a* price.
    const resolveOfferPrice = (offer) => {
        let rawPrice = offer.price ?? offer.lowPrice;
        let currency = offer.priceCurrency;
        if (rawPrice !== undefined && rawPrice !== null) {
            return { rawPrice, currency };
        }
        const raw = offer.priceSpecification;
        const specs = Array.isArray(raw) ? raw : (raw ? [raw] : []);
        const valid = specs
            .map(s => ({ spec: s, num: parseNumeric(s && s.price) }))
            .filter(x => !isNaN(x.num) && x.num > 0 && isProductPriceType(x.spec && x.spec['@type']));
        if (valid.length === 0) return { rawPrice: undefined, currency };
        const survivors = valid.filter(x => !isMsrpType(x.spec.priceType));
        const pool = survivors.length > 0 ? survivors : valid;
        const chosen = pool.reduce((min, x) => x.num < min.num ? x : min);
        return {
            rawPrice: chosen.spec.price,
            currency: currency || chosen.spec.priceCurrency,
        };
    };

    // schema.org @type -> lowercase leaf token (bare "Product" | full IRI ".../Product" | array).
    // Identical to the copy in site_name.js (separate page.evaluate contexts can't share);
    // mirrored fixtures exercise both copies. Char class (no backslash escapes) keeps the regex simple;
    // filter(Boolean) drops empty segments so a trailing / or # doesn't collapse the leaf to "".
    const leafType = (x) => {
        if (typeof x !== 'string') return '';
        const tokens = x.trim().split(/[/#]/).filter(Boolean);
        return (tokens.pop() || '').toLowerCase();
    };
    const typeTokens = (t) => (Array.isArray(t) ? t : [t]).map(leafType).filter(Boolean);
    const PRODUCT_TYPES = ['product', 'individualproduct'];
    const OFFER_TYPES = ['offer', 'aggregateoffer'];

    // Resolve a price from one JSON-LD node/item: gather its offers (a Product's offers[], or the
    // node itself when it is an Offer / dual-typed with an inline price) and return the first that
    // yields a valid price, else null. Extracted so the Tier 1a scan loop below stays shallow.
    const offerFromItem = (item) => {
        const types = typeTokens(item['@type']);
        const isProduct = types.some(t => PRODUCT_TYPES.includes(t));
        const isOffer = types.some(t => OFFER_TYPES.includes(t));
        let offers = [];
        if (isProduct) {
            const raw = item.offers || item.offer;
            offers = Array.isArray(raw) ? raw : (raw ? [raw] : []);
        }
        if (offers.length === 0 && isOffer) offers = [item]; // dual ["Product","Offer"] w/ inline price
        for (const offer of offers) {
            if (!offer || typeof offer !== 'object' || Array.isArray(offer)) continue;
            const { rawPrice, currency } = resolveOfferPrice(offer);
            const result = buildResult(parseNumeric(rawPrice), currency, offer.availability);
            if (result) return result;
        }
        return null;
    };

    // Tier 1a: JSON-LD
    const scripts = Array.from(document.querySelectorAll('script[type="application/ld+json"]'));
    for (const script of scripts) {
        try {
            const data = JSON.parse(script.textContent);
            const nodes = Array.isArray(data) ? data : [data];
            for (const node of nodes) {
                if (!node || typeof node !== 'object' || Array.isArray(node)) continue;
                // Scan the node itself AND its @graph entries (single-object @graph tolerated), so a
                // top-level Product that also carries an auxiliary @graph isn't dropped.
                const graph = node['@graph'];
                const graphNodes = Array.isArray(graph) ? graph : (graph && typeof graph === 'object' ? [graph] : []);
                for (const item of [node, ...graphNodes]) {
                    if (!item || typeof item !== 'object' || Array.isArray(item)) continue;
                    const result = offerFromItem(item);
                    if (result) return result;
                }
            }
        } catch (e) {}
    }

    // Tier 1b: Schema.org Microdata fallback. Suffix match on itemtype covers
    // 'https://schema.org/Offer' and the legacy http variant.
    const readProp = (root, name) => {
        const el = root.querySelector('[itemprop="' + name + '"]');
        if (!el) return null;
        const v = el.getAttribute('content')
            || el.getAttribute('href')
            || (el.innerText || '').trim();
        return v || null;
    };
    const offerEls = document.querySelectorAll(
        '[itemtype$="/Offer"], [itemtype$="/AggregateOffer"]'
    );
    for (const offerEl of offerEls) {
        const rawPrice = readProp(offerEl, 'price') ?? readProp(offerEl, 'lowPrice');
        const currency = readProp(offerEl, 'priceCurrency');
        const availability = readProp(offerEl, 'availability') || '';
        const result = buildResult(parseNumeric(rawPrice), currency, availability);
        if (result) return result;
    }

    return null;
}
