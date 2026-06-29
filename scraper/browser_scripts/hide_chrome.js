// @ts-check
/** @returns {void} */
() => {
    const styleId = 'scraper-hide-chrome';
    if (document.getElementById(styleId)) return;
    const selectors = [
        'nav', 'footer',
        '[class*="cookie"]', '[class*="banner"]', '[class*="ad-"]',
        '[id*="cookie"]', '[id*="popup"]'
    ];
    const style = document.createElement('style');
    style.id = styleId;
    style.textContent = selectors.map(s => s + ' { display: none !important; }').join('\n');
    (document.head || document.documentElement).appendChild(style);
}
