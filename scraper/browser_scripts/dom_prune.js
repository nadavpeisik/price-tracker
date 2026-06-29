// @ts-check
/** @returns {void} */
() => {
    const selectors = [
        'nav', 'footer', 'script', 'noscript',
        '[class*="cookie"]', '[class*="banner"]', '[class*="ad-"]',
        '[id*="cookie"]', '[id*="popup"]'
    ];
    selectors.forEach(s => document.querySelectorAll(s).forEach(el => el.remove()));
}
