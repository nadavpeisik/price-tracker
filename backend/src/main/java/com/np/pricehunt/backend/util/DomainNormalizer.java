package com.np.pricehunt.backend.util;

import com.google.common.net.InternetDomainName;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Derives the Public-Suffix-List registrable domain from a URL (the key for the shop-name mapping
 * table) and a human-friendly fallback label from that domain. Pure and stateless.
 */
public final class DomainNormalizer {

    private DomainNormalizer() {}

    /**
     * The registrable domain used as the mapping key, e.g. {@code https://www.super-pharm.co.il/x}
     * → {@code super-pharm.co.il}. Lowercased and punycoded (ASCII) so IDN hosts key consistently.
     * IPs/localhost are returned as-is; an unparseable URL yields {@code null}.
     */
    public static String registrableDomain(String url) {
        String host = host(url);
        if (host == null || host.isBlank()) {
            return null;
        }
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        host = host.toLowerCase(Locale.ROOT);
        String ascii;
        try {
            ascii = IDN.toASCII(host);
        } catch (IllegalArgumentException e) {
            ascii = host;
        }
        if (isIpOrLocalhost(ascii)) {
            return ascii;
        }
        try {
            InternetDomainName name = InternetDomainName.from(ascii);
            if (name.isUnderPublicSuffix()) {
                return name.topPrivateDomain().toString();
            }
            return ascii;
        } catch (IllegalArgumentException | IllegalStateException e) {
            // Not a valid/registrable domain (and not an IP we recognised) — best-effort: drop www.
            return ascii.startsWith("www.") ? ascii.substring(4) : ascii;
        }
    }

    /**
     * A display label for the host fallback, e.g. {@code super-pharm.co.il} → {@code Super Pharm}.
     * Uses the first label of the registrable domain, unpunycoded for RTL/IDN names. IPs/localhost
     * are returned unchanged.
     */
    public static String prettyLabel(String registrableDomain) {
        if (registrableDomain == null || registrableDomain.isBlank()) {
            return registrableDomain;
        }
        String unicode;
        try {
            unicode = IDN.toUnicode(registrableDomain);
        } catch (IllegalArgumentException e) {
            unicode = registrableDomain;
        }
        if (isIpOrLocalhost(unicode)) {
            return unicode;
        }
        String label;
        try {
            label = InternetDomainName.from(unicode).parts().get(0);
        } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException e) {
            label = unicode;
        }
        return titleCase(label.replace('-', ' ').replace('.', ' ').trim());
    }

    // URI.getHost() returns null for some authorities (e.g. underscores in the host); fall back to
    // the authority with userinfo and port stripped. IPv6 literals are bracketed ([::1]:8080), so
    // the port colon must be located after the closing bracket, not inside the address.
    private static String host(String url) {
        try {
            URI uri = new URI(url.trim());
            String host = uri.getHost();
            if (host != null) {
                return host;
            }
            String authority = uri.getAuthority();
            if (authority == null) {
                return null;
            }
            int at = authority.indexOf('@');
            if (at >= 0) {
                authority = authority.substring(at + 1);
            }
            if (authority.startsWith("[")) {
                int close = authority.indexOf(']');
                return close >= 0 ? authority.substring(0, close + 1) : authority;
            }
            int colon = authority.indexOf(':');
            return colon >= 0 ? authority.substring(0, colon) : authority;
        } catch (URISyntaxException | NullPointerException e) {
            return null;
        }
    }

    private static boolean isIpOrLocalhost(String host) {
        return "localhost".equals(host)
                || host.indexOf(':') >= 0 // IPv6
                || host.matches("\\d{1,3}(\\.\\d{1,3}){3}"); // IPv4
    }

    private static String titleCase(String value) {
        if (value.isBlank()) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (String word : value.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) {
                out.append(word.substring(1));
            }
        }
        return out.toString();
    }
}
