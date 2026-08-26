package com.np.pricehunt.backend.validator;

import com.np.pricehunt.backend.config.UrlValidationProperties;
import com.np.pricehunt.backend.exception.ApplicationException;
import com.np.pricehunt.backend.exception.DependencyTimeoutException;
import com.np.pricehunt.backend.exception.DependencyUnavailableException;
import com.np.pricehunt.backend.exception.ValidationException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Validates user-submitted / stored URLs before they reach the scraper.
 *
 * <p>{@link #validate(String)} is the single gate for <b>every</b> path that reaches the scraper (new
 * track, manual refresh, scheduler): parser-differential string checks + the SSRF/internal-host check +
 * the operational unsupported-site blocklist. The blocklist is a <b>safety control</b> — it stops us
 * hammering sites with anti-bot walls that could get our IP flagged — so it is enforced everywhere:
 * blocklisting a host halts ALL outbound requests to it, including refreshes of already-tracked items.
 * The scheduler additionally calls {@link #isNeverScrapable(String)} to skip such items up front
 * (a clean skip, not a failed scrape attempt) so they are never even attempted.
 *
 * <p>{@code .invalid} and {@code localhost} are rejected <b>unconditionally</b> —
 * {@code unsupportedSitesEnabled} does not gate them. Neither can name a real shop, so treating them
 * as a configurable product decision is what let a run with the blocklist switched off spend a DNS
 * lookup and a FAILED job-run item on every seeded {@code .invalid} listing, once per scheduler pass.
 *
 * <p>The SSRF host check ({@link #assertHostAllowed}) resolves the host and rejects any resolved IP in a
 * private / loopback / link-local / CGNAT / ULA / multicast / reserved range or a cloud-metadata
 * endpoint. It is <b>best-effort defense-in-depth</b>: the scraper is a separate service that re-resolves
 * DNS and follows redirects, so the authoritative control is scraper-side (filed follow-ups).
 */
@Slf4j
@Component
public class UrlValidator {

    /**
     * The two special-use names RFC 6761 lets an application recognise without asking a resolver:
     * {@code invalid} (§6.4, "MAY recognize ... as special" — and it is guaranteed not to exist) and
     * {@code localhost} (§6.3). Matched on the last label, so every subdomain is covered.
     *
     * <p>The list is deliberately this short. {@code test} (§6.2) and {@code example} (§6.5) carry the
     * opposite instruction — application software SHOULD NOT treat them as special — so blocking them
     * here would contradict the standard that justifies the entry above it. {@code local} (RFC 6762) is
     * a genuine SSRF surface rather than a nonexistent name, and the resolved-IP check below already
     * rejects the link-local addresses mDNS hands back; promoting it to a pre-DNS block is a network
     * policy decision for the SSRF follow-ups, not a side effect of skipping dev-seed data.
     */
    private static final Set<String> RESERVED_TLDS = Set.of("invalid", "localhost");

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
     * an {@link ApplicationException} subtype on any rejection.
     */
    public void validate(String url) {
        HostContext ctx = validateCommon(url);
        // Cheap regex blocklist BEFORE the DNS-based SSRF check, so a blocklisted host 400s without
        // consuming a resolver slot.
        rejectUnsupportedSites(ctx.bareLc());
        assertHostAllowed(ctx.bare());
    }

    /**
     * Non-throwing predicate: would {@link #validate} reject {@code url} on a check that needs no DNS —
     * a reserved special-use name, or a host on the unsupported-site blocklist? The scheduler uses it to
     * skip such items up front (a clean skip, not a FAILED scrape attempt), so we never send them a
     * request nor consume a resolver slot. Parses defensively — an unparseable / host-less URL returns
     * {@code false} and flows on to full {@link #validate}, which will reject it there.
     */
    public boolean isNeverScrapable(String url) {
        if (url == null) {
            return false;
        }
        try {
            String host = new URI(url).getHost();
            if (host == null) {
                return false;
            }
            String hostLc = stripIpv6Brackets(host).toLowerCase(Locale.ROOT);
            return isReservedName(hostLc) || matchesBlocklist(hostLc);
        } catch (URISyntaxException e) {
            return false;
        }
    }

    // The bracket-stripped host plus its lowercase form, produced once by validateCommon.
    private record HostContext(String bare, String bareLc) {}

    private HostContext validateCommon(String url) {
        if (url == null || url.isBlank()) {
            throw new ValidationException("Invalid URL");
        }
        URI uri = parseOrThrow(url);
        String host = uri.getHost();
        String scheme = uri.getScheme();
        if (host == null || scheme == null) {
            throw new ValidationException("Invalid URL");
        }
        String normalizedScheme = scheme.toLowerCase(Locale.ROOT);
        if (!normalizedScheme.equals("http") && !normalizedScheme.equals("https")) {
            throw new ValidationException("URL must be http or https");
        }

        // Strip IPv6 brackets — URI.getHost() keeps them ("[::1]"); getByName("[::1]") throws.
        String bare = stripIpv6Brackets(host);

        // Reject any trailing-dot host outright (#139 review). We must resolve exactly what the scraper
        // fetches (it gets the raw URL, dot kept); stripping the dot would let us validate a different
        // name than gets requested — and in high-ndots/search-domain envs the two resolve differently.
        // No legitimate product host ends in a dot. Also catches octal/hex IP literals hiding a trailing
        // dot (e.g. "0177.0.0.1.") and a dot inside IPv6 brackets ("[::ffff:1.2.3.4.]").
        if (bare.endsWith(".")) {
            throw new ValidationException("URL host is not allowed");
        }

        String bareLc = bare.toLowerCase(Locale.ROOT);

        // (0) Strict host character whitelist (defense-in-depth): normal domains + IP literals only —
        // letters / digits / dot / hyphen, plus ':' for the IPv6 literal we already de-bracketed. Rejects
        // anything a lenient scraper parser might interpret differently.
        if (!bareLc.matches("[a-z0-9.:-]+")) {
            throw new ValidationException("URL host is not allowed");
        }

        // (a) Reject ANY userinfo / backslash / percent-encoding in the authority — no legit host needs
        // them, and each is a scraper parser-differential vector (blanket '%' subsumes %5c %2f %40 …).
        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority != null
                && (rawAuthority.indexOf('@') >= 0
                        || rawAuthority.indexOf('\\') >= 0
                        || rawAuthority.indexOf('%') >= 0)) {
            throw new ValidationException("URL host is not allowed");
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
            throw new ValidationException("URL host is not allowed");
        }

        // (c) Reject the reserved names in RESERVED_TLDS WITHOUT DNS — see that constant for why the
        // list stops where it does.
        if (isReservedName(bareLc)) {
            throw new ValidationException("URL host is not allowed");
        }

        return new HostContext(bare, bareLc);
    }

    // Last-label match: "seed.invalid" and "ivory.seed.invalid" both reduce to "invalid". An IP literal
    // never matches (its last label is numeric, or the whole string for a colon-bearing IPv6 address).
    private static boolean isReservedName(String hostLc) {
        int lastDot = hostLc.lastIndexOf('.');
        return RESERVED_TLDS.contains(lastDot < 0 ? hostLc : hostLc.substring(lastDot + 1));
    }

    private static String stripIpv6Brackets(String host) {
        return host.length() > 1 && host.charAt(0) == '[' && host.charAt(host.length() - 1) == ']'
                ? host.substring(1, host.length() - 1)
                : host;
    }

    private URI parseOrThrow(String url) {
        try {
            return new URI(url);
        } catch (URISyntaxException e) {
            throw new ValidationException("Invalid URL");
        }
    }

    private void rejectUnsupportedSites(String host) {
        if (matchesBlocklist(host)) {
            throw new ValidationException("URLs from " + host + " are not currently supported");
        }
    }

    // Shared by rejectUnsupportedSites (throwing, in validate) and isNeverScrapable (predicate, for the
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
            throw new ValidationException("Unable to resolve host", e);
        } catch (TimeoutException e) {
            log.warn("SSRF check: host resolution timed out: {}", h);
            throw new DependencyTimeoutException("Host resolution timed out", e);
        } catch (HostResolutionUnavailableException e) {
            // Keep the cause chain (saturation vs interruption) for diagnosing bulkhead pressure.
            log.warn("SSRF check: resolver unavailable for: {}", h, e);
            throw new DependencyUnavailableException("Host resolution unavailable", e);
        }
        if (addrs == null || addrs.length == 0) {
            throw new ValidationException("Unable to resolve host");
        }
        for (InetAddress a : addrs) {
            if (isBlocked(a)) {
                throw new ValidationException("URL host is not allowed");
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
