package com.np.pricehunt.backend.validator;

import com.np.pricehunt.backend.config.UrlValidationProperties;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates user-submitted / stored URLs before they reach the scraper.
 *
 * <p>{@link #validate(String)} is the single gate for <b>every</b> path that reaches the scraper (new
 * track, manual refresh, scheduler): parser-differential string checks + the SSRF/internal-host check +
 * the operational unsupported-site blocklist. The blocklist is a <b>safety control</b> — it stops us
 * hammering sites with anti-bot walls that could get our IP flagged — so it is enforced everywhere:
 * blocklisting a host halts ALL outbound requests to it, including refreshes of already-tracked items.
 * The scheduler additionally calls {@link #isUnsupportedHost(String)} to skip blocklisted items up front
 * (a clean skip, not a failed scrape attempt) so they are never even attempted.
 *
 * <p>The SSRF host check ({@link #assertHostAllowed}) resolves the host and rejects any resolved IP in a
 * private / loopback / link-local / CGNAT / ULA / multicast / reserved range or a cloud-metadata
 * endpoint. It is <b>best-effort defense-in-depth</b>: the scraper is a separate service that re-resolves
 * DNS and follows redirects, so the authoritative control is scraper-side (filed follow-ups).
 */
@Slf4j
@Component
public class UrlValidator {

    private final boolean unsupportedSitesEnabled;
    private final List<Pattern> blockedHostPatterns;
    private final HostResolver hostResolver;

    public UrlValidator(UrlValidationProperties properties, HostResolver hostResolver) {
        this.hostResolver = hostResolver;
        this.unsupportedSitesEnabled = properties.unsupportedSitesEnabled();
        this.blockedHostPatterns = properties.unsupportedHostPatterns().stream()
                .filter(s -> !s.isBlank())
                .map(s -> Pattern.compile(s, Pattern.CASE_INSENSITIVE))
                .toList();
        if (!unsupportedSitesEnabled) {
            log.warn("Unsupported-sites blocklist is DISABLED via configuration; the UX site blocklist is "
                    + "skipped, but the SSRF/internal-host checks remain active.");
        }
    }

    /**
     * Full validation for every path that reaches the scraper: parser-differential string checks + the
     * operational unsupported-site blocklist + the SSRF/internal-host check. Throws
     * {@link ResponseStatusException} on any rejection.
     */
    public void validate(String url) {
        HostContext ctx = validateCommon(url);
        // Cheap regex blocklist BEFORE the DNS-based SSRF check, so a blocklisted host 400s without
        // consuming a resolver slot.
        rejectUnsupportedSites(ctx.bareLc());
        assertHostAllowed(ctx.bare());
    }

    /**
     * Non-throwing predicate: is {@code url}'s host on the operational unsupported-site blocklist? The
     * scheduler uses this to skip blocklisted items up front (a clean skip, not a FAILED scrape attempt),
     * so we never send them a request. Parses defensively — an unparseable / host-less URL returns
     * {@code false} and flows on to full {@link #validate}, which will reject it there.
     */
    public boolean isUnsupportedHost(String url) {
        if (!unsupportedSitesEnabled || url == null) {
            return false;
        }
        try {
            String host = new URI(url).getHost();
            return host != null && matchesBlocklist(host.toLowerCase(Locale.ROOT));
        } catch (URISyntaxException e) {
            return false;
        }
    }

    // The bracket-stripped host plus its lowercase form, produced once by validateCommon.
    private record HostContext(String bare, String bareLc) {}

    private HostContext validateCommon(String url) {
        if (url == null || url.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid URL");
        }
        URI uri = parseOrThrow(url);
        String host = uri.getHost();
        String scheme = uri.getScheme();
        if (host == null || scheme == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid URL");
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!normalizedScheme.equals("http") && !normalizedScheme.equals("https")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL must be http or https");
        }

        // Strip IPv6 brackets — URI.getHost() keeps them ("[::1]"); getByName("[::1]") throws.
        String bare = host;
        if (bare.startsWith("[") && bare.endsWith("]")) {
            bare = bare.substring(1, bare.length() - 1);
        }

        // Reject any trailing-dot host outright (#139 review). We must resolve exactly what the scraper
        // fetches (it gets the raw URL, dot kept); stripping the dot would let us validate a different
        // name than gets requested — and in high-ndots/search-domain envs the two resolve differently.
        // No legitimate product host ends in a dot. Also catches octal/hex IP literals hiding a trailing
        // dot (e.g. "0177.0.0.1.") and a dot inside IPv6 brackets ("[::ffff:1.2.3.4.]").
        if (bare.endsWith(".")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL host is not allowed");
        }

        String bareLc = bare.toLowerCase(Locale.ROOT);

        // (0) Strict host character whitelist (defense-in-depth): normal domains + IP literals only —
        // letters / digits / dot / hyphen, plus ':' for the IPv6 literal we already de-bracketed. Rejects
        // anything a lenient scraper parser might interpret differently.
        if (!bareLc.matches("[a-z0-9.:-]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL host is not allowed");
        }

        // (a) Reject ANY userinfo / backslash / percent-encoding in the authority — no legit host needs
        // them, and each is a scraper parser-differential vector (blanket '%' subsumes %5c %2f %40 …).
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority != null
                && (rawAuthority.indexOf('@') >= 0
                        || rawAuthority.indexOf('\\') >= 0
                        || rawAuthority.indexOf('%') >= 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL host is not allowed");
        }

        // (b) Reject non-canonical IPv4-literal attempts (octal / hex / integer / short) — whole-host OR
        // the IPv6-embedded dotted tail. Scoped to IP-literal-SHAPED hosts so a legit domain that merely
        // contains "0x" (e.g. sony0x.com) is NOT rejected.
        String ipv4Literal;
        if (bareLc.indexOf(':') < 0) {
            ipv4Literal = bareLc; // no colon → candidate IPv4 literal (or a hostname)
        } else {
            String tail = bareLc.substring(bareLc.lastIndexOf(':') + 1); // IPv6 literal: only the dotted tail
            ipv4Literal = tail.indexOf('.') >= 0 ? tail : null; // avoids false-positives on hex groups (0db8)
        }
        if (ipv4Literal != null && isIpv4LiteralAttempt(ipv4Literal) && !isCanonicalDecimalQuad(ipv4Literal)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL host is not allowed");
        }

        // (c) Reject the loopback alias `localhost` (+ any `*.localhost`) WITHOUT DNS — runtimes/browsers
        // special-case it and system resolution can differ (RFC 6761 reserves *.localhost as loopback).
        if (bareLc.equals("localhost") || bareLc.endsWith(".localhost")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL host is not allowed");
        }

        return new HostContext(bare, bareLc);
    }

    private URI parseOrThrow(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid URL");
        }
    }

    private void rejectUnsupportedSites(String host) {
        if (matchesBlocklist(host)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "URLs from " + host + " are not currently supported");
        }
    }

    // Shared by rejectUnsupportedSites (throwing, in validate) and isUnsupportedHost (predicate, for the
    // scheduler). Honors the enable flag so a disabled blocklist matches nothing.
    private boolean matchesBlocklist(String hostLc) {
        if (!unsupportedSitesEnabled) {
            return false;
        }
        for (Pattern pattern : blockedHostPatterns) {
            if (pattern.matcher(hostLc).find()) {
                return true;
            }
        }
        return false;
    }

    // Resolve the host and reject if ANY resolved IP is internal — defeats split public/private A-records.
    private void assertHostAllowed(String h) {
        InetAddress[] addrs;
        try {
            addrs = hostResolver.resolve(h);
        } catch (UnknownHostException e) {
            log.debug("SSRF check: host did not resolve: {}", h); // typos/scans are common → DEBUG
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to resolve host", e);
        } catch (TimeoutException e) {
            log.warn("SSRF check: host resolution timed out: {}", h);
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Host resolution timed out", e);
        } catch (HostResolutionUnavailableException e) {
            // Keep the cause chain (saturation vs interruption) for diagnosing bulkhead pressure.
            log.warn("SSRF check: resolver unavailable for: {}", h, e);
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Host resolution unavailable", e);
        }
        if (addrs == null || addrs.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unable to resolve host");
        }
        for (InetAddress a : addrs) {
            if (isBlocked(a)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL host is not allowed");
            }
        }
    }

    // Blocks private/loopback/link-local/CGNAT/ULA/multicast/reserved/metadata + the two standard NAT64
    // prefixes. NOT a full IANA special-purpose enumeration — globally-unroutable special-use ranges and
    // custom/operator NAT64 prefixes are the scraper connect-time / egress layer's job (follow-ups).
    private boolean isBlocked(InetAddress a) {
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            byte[] v4 = embeddedIpv4(b); // fixes both the mapped/6to4 bypass AND the DNS64/NAT64 break
            if (v4 != null) {
                return isBlockedV4(v4);
            }
            if (((b[0] & 0xff) & 0xfe) == 0xfc) {
                return true; // fc00::/7 ULA — isSiteLocalAddress MISSES this (only fec0::/10)
            }
            if (isLocalUseNat64(b)) {
                return true; // 64:ff9b:1::/48 (RFC 8215) — operator-internal, blocked wholesale
            }
            return a.isLoopbackAddress() // ::1
                    || a.isAnyLocalAddress() // ::
                    || a.isLinkLocalAddress() // fe80::/10
                    || a.isSiteLocalAddress() // fec0::/10
                    || a.isMulticastAddress(); // ff00::/8
        }
        return isBlockedV4(b);
    }

    // All literals are HEX where noted; write them as 0x.. — a decimal typo here is a silent bypass.
    // SIGNED-BYTE + PRECEDENCE: Java `byte` is signed, so `b[i] == 0xff` is ALWAYS false ((byte)0xff == -1);
    // mask every comparison `(b[i] & 0xff)` and PARENTHESIZE — `==` binds tighter than `&`.
    private boolean isBlockedV4(byte[] b) {
        int b0 = b[0] & 0xff;
        int b1 = b[1] & 0xff;
        if (b0 == 0) {
            return true; // 0.0.0.0/8 "this host"
        }
        if (b0 == 10) {
            return true; // 10/8 private
        }
        if (b0 == 127) {
            return true; // 127/8 loopback
        }
        if (b0 == 169 && b1 == 254) {
            return true; // 169.254/16 link-local — incl. 169.254.169.254 AWS/GCP/Azure metadata
        }
        if (b0 == 172 && b1 >= 16 && b1 <= 31) {
            return true; // 172.16/12 private
        }
        if (b0 == 192 && b1 == 168) {
            return true; // 192.168/16 private
        }
        if (b0 == 100 && (b1 & 0xc0) == 0x40) {
            return true; // 100.64/10 CGNAT — incl. Alibaba metadata 100.100.100.200
        }
        if (b0 == 192 && b1 == 0 && (b[2] & 0xff) == 0) {
            return true; // 192.0.0.0/24 — incl. 192.0.0.192 Oracle OCI metadata
        }
        if (b0 == 198 && (b1 == 18 || b1 == 19)) {
            return true; // 198.18/15 benchmarking
        }
        return b0 >= 224; // 224/4 multicast + 240/4 reserved + 255.255.255.255 broadcast
    }

    // Extract an embedded IPv4 as a FRESH 4-byte array (never a slice/view of `b`). Returns null if none.
    private static byte[] embeddedIpv4(byte[] b) {
        if (allZero(b, 0, 10) && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
            return Arrays.copyOfRange(b, 12, 16); // IPv4-mapped ::ffff:0:0/96
        }
        if (allZero(b, 0, 8)
                && (b[8] & 0xff) == 0xff
                && (b[9] & 0xff) == 0xff
                && (b[10] & 0xff) == 0x00
                && (b[11] & 0xff) == 0x00) {
            return Arrays.copyOfRange(b, 12, 16); // IPv4-translated ::ffff:0:0/96 (SIIT, RFC 2765)
        }
        if (allZero(b, 0, 12)) {
            return Arrays.copyOfRange(b, 12, 16); // IPv4-compatible ::/96 (also catches :: and ::1 as 0.0.0.x)
        }
        if ((b[0] & 0xff) == 0x00
                && (b[1] & 0xff) == 0x64
                && (b[2] & 0xff) == 0xff
                && (b[3] & 0xff) == 0x9b
                && allZero(b, 4, 12)) {
            return Arrays.copyOfRange(b, 12, 16); // NAT64 well-known 64:ff9b::/96
        }
        if ((b[0] & 0xff) == 0x20 && (b[1] & 0xff) == 0x02) {
            return Arrays.copyOfRange(b, 2, 6); // 6to4 2002::/16
        }
        return null;
    }

    // 64:ff9b:1::/48 (RFC 8215) — its RFC-6052 embedding offset is prefix-length-dependent and it is
    // operator-internal, so it is blocked wholesale rather than having an IPv4 extracted.
    private static boolean isLocalUseNat64(byte[] b) {
        return (b[0] & 0xff) == 0x00
                && (b[1] & 0xff) == 0x64
                && (b[2] & 0xff) == 0xff
                && (b[3] & 0xff) == 0x9b
                && (b[4] & 0xff) == 0x00
                && (b[5] & 0xff) == 0x01;
    }

    private static boolean allZero(byte[] b, int fromInclusive, int toExclusive) {
        for (int i = fromInclusive; i < toExclusive; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    // An IP-literal ATTEMPT = every dot-label is a decimal or 0x-hex number (NOT a real hostname). So
    // "8.8.8.8", "0x7f000001", "0177.0.0.1", "2130706433" qualify; "sony0x.com", "www.thomann.de" do not.
    private static boolean isIpv4LiteralAttempt(String s) {
        for (String l : s.split("\\.", -1)) {
            if (!l.matches("0x[0-9a-f]+|[0-9]+")) {
                return false;
            }
        }
        return true;
    }

    // Canonical decimal dotted quad only. Rejects 0177.0.0.1 (octal), 0x7f.0.0.1 (hex), 2130706433
    // (integer form), 127.0.1 (3 parts), 127.0.0.256 (>255), 127.0.0.99999999999 (len>3). The digit-only
    // guard ⇒ parseInt never overflows/throws ⇒ always 400, never 500.
    private static boolean isCanonicalDecimalQuad(String s) {
        String[] p = s.split("\\.", -1);
        if (p.length != 4) {
            return false;
        }
        for (String o : p) {
            if (o.isEmpty() || o.length() > 3) {
                return false;
            }
            for (int i = 0; i < o.length(); i++) {
                char c = o.charAt(i);
                if (c < '0' || c > '9') {
                    return false; // ASCII digits only (Character.isDigit passes Unicode digits ⇒ parseInt risk)
                }
            }
            if (o.length() > 1 && o.charAt(0) == '0') {
                return false; // leading zero
            }
            if (Integer.parseInt(o) > 255) {
                return false;
            }
        }
        return true;
    }
}
