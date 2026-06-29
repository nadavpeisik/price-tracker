// @ts-check
/** @returns {{name:string, strong:boolean}|null} */
() => {
    // Strip Unicode bidi/directional control chars (RTL pages wrap titles in them) so a leading
    // mark can't glue to a segment and defeat the og:title de-dup below; then trim.
    const clean = (s) => {
        if (typeof s !== 'string') return '';
        let out = '';
        for (const ch of s) {
            const c = ch.codePointAt(0);
            if ((c >= 0x200e && c <= 0x200f) || (c >= 0x202a && c <= 0x202e) || (c >= 0x2066 && c <= 0x2069)) continue;
            out += ch;
        }
        return out.trim();
    };

    // Tier A: OpenGraph site name — strong.
    const og = document.querySelector('meta[property="og:site_name"]');
    const ogName = og ? clean(og.getAttribute('content')) : '';
    if (ogName) return { name: ogName, strong: true };

    // Collect JSON-LD nodes at the top level only (each block's top-level entries + one level of
    // @graph) — deliberately NOT recursing into nested objects, so a nested offers.seller
    // Organization can't be mistaken for the storefront. Each block parsed in its own try/catch.
    const nodes = [];
    const addTop = (data) => {
        const arr = Array.isArray(data) ? data : [data];
        for (const n of arr) {
            if (!n || typeof n !== 'object') continue;
            nodes.push(n);
            if (Array.isArray(n['@graph'])) {
                for (const g of n['@graph']) if (g && typeof g === 'object') nodes.push(g);
            }
        }
    };
    for (const s of document.querySelectorAll('script[type="application/ld+json"]')) {
        try { addTop(JSON.parse(s.textContent)); } catch (e) {}
    }
    const typesOf = (n) => {
        const t = n && n['@type'];
        if (!t) return [];
        return (Array.isArray(t) ? t : [t]).map(x => String(x).toLowerCase());
    };
    const ORG_TYPES = ['organization', 'website', 'store', 'onlinestore', 'corporation', 'localbusiness'];
    const stripWww = (h) => (h.startsWith('www.') ? h.slice(4) : h);
    const pageHost = stripWww((location.hostname || '').toLowerCase());
    const hostOf = (v) => {
        if (typeof v !== 'string') return '';
        const t = v.trim();
        const s = t.startsWith('//') ? 'https:' + t : t; // resolve protocol-relative urls
        if (!(s.startsWith('http://') || s.startsWith('https://'))) return ''; // ignore relative / #frag @id
        try { return stripWww(new URL(s).hostname.toLowerCase()); } catch (e) { return ''; }
    };
    const sameSite = (h) => h === pageHost || h.endsWith('.' + pageHost) || pageHost.endsWith('.' + h);

    // Tier B: Schema.org Organization/WebSite/Store name. An org whose url/@id host MATCHES the page
    // is the site publisher → strong (safe to learn into the shared domain mapping). An org on a
    // DIFFERENT host is a brand/manufacturer (e.g. url=sony.com on a shop page) → skipped, so it can
    // never poison the domain mapping. An org with no resolvable host is shown but NOT learned (weak).
    let weakOrgName = null;
    for (const n of nodes) {
        if (!(typesOf(n).some(t => ORG_TYPES.includes(t)) && clean(n.name))) continue;
        const orgHost = hostOf(n.url) || hostOf(n['@id']);
        if (orgHost && sameSite(orgHost)) {
            return { name: clean(n.name), strong: true };
        }
        if (!orgHost && weakOrgName === null) {
            weakOrgName = clean(n.name);
        }
    }
    if (weakOrgName !== null) {
        return { name: weakOrgName, strong: false };
    }

    // Tier C: <title> heuristic — weak. A title with no separator is just the product name (no
    // shop segment), so it is rejected outright (return null) rather than used verbatim — the
    // product name must never be mistaken for the shop name. Only a title with a real separator is
    // mined for a shop segment: split on pipe / en- / em-dash / spaced ascii hyphen (so "SLO-30" /
    // "Wi-Fi" don't split), else fall back to colon. Drop the product segment (anything contained
    // in og:title) and the bare-host segment; accept only if exactly one segment survives.
    const title = clean(document.title);
    if (title) {
        let parts = title.split(/\s+[|\u2013\u2014-]\s+/).map(clean).filter(Boolean);
        if (parts.length < 2) parts = title.split(/\s*:\s*/).map(clean).filter(Boolean);
        if (parts.length >= 2) {
            const ot = document.querySelector('meta[property="og:title"]');
            const ogTitle = (ot ? clean(ot.getAttribute('content')) : '').toLowerCase();
            const host = (location.hostname || '').replace(/^www\./, '').toLowerCase();
            const survivors = parts.filter(p => {
                const pl = p.toLowerCase();
                if (ogTitle && ogTitle.includes(pl)) return false;
                if (host && pl === host) return false;
                return true;
            });
            if (survivors.length === 1) return { name: survivors[0], strong: false };
        }
    }

    return null;
}
