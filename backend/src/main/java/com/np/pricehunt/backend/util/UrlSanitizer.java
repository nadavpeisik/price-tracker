package com.np.pricehunt.backend.util;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Data-minimization for URLs persisted in the scrape-attempt audit (issue #131). The audit keeps
 * {@code url} for up to the retention window even after the tracked item is deleted; "public
 * product-page text" is an assumption, not enforced ({@code UrlValidator} accepts any HTTP(S) host),
 * and a URL can carry credentials, tracking params, or identifiers. So on write we strip the fragment,
 * any authority userinfo (basic-auth credentials), and known tracking query params, keeping the
 * product-identifying path + remaining query intact.
 *
 * <p>String-level (not URI re-encoding) so it never re-escapes a valid URL or throws on an odd one —
 * minimization is best-effort and must never break the best-effort audit insert.
 */
public final class UrlSanitizer {

    private UrlSanitizer() {}

    // Exact tracking keys to drop. The "utm_" prefix is handled separately so every utm_* variant goes.
    private static final Set<String> TRACKING_PARAMS =
            Set.of("gclid", "fbclid", "msclkid", "mc_eid", "mc_cid", "igshid", "_ga", "yclid", "dclid", "gclsrc");

    /**
     * Returns {@code url} with its {@code #fragment}, authority userinfo (basic-auth credentials), and
     * known tracking query params removed.
     */
    public static String minimize(String url) {
        if (url == null || url.isBlank()) {
            return url;
        }
        String sanitized = stripUserInfo(stripFragment(url.trim()));

        int q = sanitized.indexOf('?');
        if (q < 0) {
            return sanitized;
        }
        String base = sanitized.substring(0, q);
        String query = sanitized.substring(q + 1);
        if (query.isEmpty()) {
            return base;
        }

        String cleaned = removeTrackingParams(query);
        return cleaned.isEmpty() ? base : base + "?" + cleaned;
    }

    private static String stripFragment(String url) {
        int hash = url.indexOf('#');
        return hash >= 0 ? url.substring(0, hash) : url;
    }

    // Removes "user:pass@" from the authority so persisted URLs never retain basic-auth credentials.
    // Scoped to the authority (scheme://[userinfo@]host…); an '@' later in the path/query is left intact.
    private static String stripUserInfo(String url) {
        int schemeSep = url.indexOf("://");
        int authorityStart;
        if (schemeSep >= 0) {
            authorityStart = schemeSep + 3;
        } else if (url.startsWith("//")) {
            authorityStart = 2; // scheme-relative URL: authority begins right after the leading "//"
        } else {
            authorityStart = 0;
        }
        int authorityEnd = url.length();
        for (int i = authorityStart; i < url.length(); i++) {
            char c = url.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                authorityEnd = i;
                break;
            }
        }
        int at = url.lastIndexOf('@', authorityEnd - 1);
        return at >= authorityStart ? url.substring(0, authorityStart) + url.substring(at + 1) : url;
    }

    // Keeps the ORIGINAL pair for non-tracking params (we decode only to match, never to rewrite a kept value).
    private static String removeTrackingParams(String query) {
        StringBuilder kept = new StringBuilder();
        for (String pair : query.split("&")) {
            if (pair.isEmpty() || isTrackingParam(pair)) {
                continue;
            }
            if (!kept.isEmpty()) {
                kept.append('&');
            }
            kept.append(pair);
        }
        return kept.toString();
    }

    private static boolean isTrackingParam(String pair) {
        int eq = pair.indexOf('=');
        String rawKey = eq >= 0 ? pair.substring(0, eq) : pair;
        // Decode the key before matching so a percent-encoded tracking key (e.g. utm%5Fsource) can't
        // evade the strip. Best-effort: a malformed % sequence falls back to the literal key.
        String key;
        try {
            key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            key = rawKey.toLowerCase(Locale.ROOT);
        }
        return key.startsWith("utm_") || TRACKING_PARAMS.contains(key);
    }
}
