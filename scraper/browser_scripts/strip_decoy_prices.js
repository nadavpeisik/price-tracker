// @ts-check
/** @returns {void} */
() => {
    // Digit-gated: <del>/<s>/<strike> wrapping non-numeric text (e.g.
    // <s>Sold Out</s>) is a UX signal Tier 2/3 needs for availability.
    // Only strip when the node contains numerals — that's the price-MSRP case.
    // Class selector uses [class~=] (exact whitespace-separated token) instead
    // of [class*=] so we don't accidentally strip a `.not-strikethrough` opt-out
    // class. <del>/<s>/<strike> tags catch all the semantic cases anyway.
    document.querySelectorAll('del, s, strike, [class~="strikethrough"], [class~="strikethrough-price"]')
        .forEach(n => { if (/[0-9]/.test(n.textContent || '')) n.remove(); });

    // Conditional: .regular-price means "MSRP" only when paired with a
    // .sale-price sibling. Walk up from each sale-price until we find an
    // ancestor that actually contains a regular-price. Depth cap +
    // class-based firewall guard against cross-card poisoning on PDPs with
    // related-product carousels.
    const MAX_ASCENT = 4;
    const MACRO_WORDS = ['grid', 'row', 'carousel', 'list', 'table'];
    // Legacy table-based product grids: <tr>/<table> often carry no class, so
    // a className-only firewall would miss them. Treat their tags as a hard
    // boundary regardless of class.
    const MACRO_TAGS = new Set(['TABLE', 'THEAD', 'TBODY', 'TFOOT', 'TR']);
    const isMacroLayout = (el) => {
        if (MACRO_TAGS.has(el.tagName)) return true;
        const cls = (typeof el.className === 'string') ? el.className.toLowerCase() : '';
        return MACRO_WORDS.some(w => cls.includes(w));
    };
    document.querySelectorAll('[class*="sale-price"]').forEach(saleEl => {
        let container = saleEl.parentElement;
        let depth = 0;
        while (container && depth < MAX_ASCENT) {
            const regulars = container.querySelectorAll('[class*="regular-price"]');
            if (regulars.length > 0) {
                regulars.forEach(n => n.remove());
                break;
            }
            const next = container.parentElement;
            if (!next || isMacroLayout(next)) break;
            container = next;
            depth++;
        }
    });
}
