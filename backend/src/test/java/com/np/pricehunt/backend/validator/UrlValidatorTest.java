package com.np.pricehunt.backend.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.np.pricehunt.backend.config.UrlValidationProperties;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class UrlValidatorTest {

    private static final String AMAZON_PATTERN = "(^|\\.)amazon\\.[a-z]{2,3}(\\.[a-z]{2})?$";

    // Default stub: every host resolves to a benign public IP, so the SSRF check passes and the test
    // exercises only scheme/blocklist/string logic.
    private static final HostResolver PUBLIC = resolverReturning(addr("8.8.8.8"));

    // A resolver that fails the test if consulted — proves a rejection happened at the DNS-free string
    // layer (parser-differential guards) and never reached resolution.
    private static final HostResolver FAIL_IF_CALLED = host -> {
        throw new AssertionError("resolver must not be called for host: " + host);
    };

    private final UrlValidator validator = validatorWith(AMAZON_PATTERN);

    // --- existing behaviour: scheme + unsupported-site blocklist ---

    @Test
    void validate_thomannUrl_passes() {
        assertThatCode(() -> validator.validate("https://www.thomann.de/gb/some_product.htm"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_string6Url_passes() {
        assertThatCode(() -> validator.validate("https://www.string6.co.il/product/benson-amps-preamp"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_amazonClone_passes() {
        assertThatCode(() -> validator.validate("https://amazon-clone.com/product/123"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_amazonComBareHost_rejected() {
        assertAmazonRejected("https://amazon.com/dp/B000000000");
    }

    @Test
    void validate_amazonComWithSubdomain_rejected() {
        assertAmazonRejected("https://www.amazon.com/dp/B000000000");
    }

    @Test
    void validate_amazonCoUk_rejected() {
        assertAmazonRejected("https://www.amazon.co.uk/dp/B000000000");
    }

    @Test
    void validate_amazonComBr_rejected() {
        assertAmazonRejected("https://www.amazon.com.br/dp/B000000000");
    }

    @Test
    void validate_amazonDe_rejected() {
        assertAmazonRejected("https://www.amazon.de/dp/B000000000");
    }

    @Test
    void validate_uppercaseHost_rejected() {
        assertAmazonRejected("https://WWW.AMAZON.COM/dp/B000000000");
    }

    @Test
    void validate_malformedUrl_rejected() {
        assertThatThrownBy(() -> validator.validate("not a url"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid URL");
    }

    @Test
    void validate_mailtoNoHost_rejected() {
        assertThatThrownBy(() -> validator.validate("mailto:foo@bar.com"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Invalid URL");
    }

    @Test
    void validate_ftpScheme_rejected() {
        assertThatThrownBy(() -> validator.validate("ftp://example.com/file"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("http or https");
                });
    }

    @Test
    void validate_uppercaseHttpsScheme_passes() {
        assertThatCode(() -> validator.validate("HTTPS://www.thomann.de/gb/some_product.htm"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_emptyBlocklist_amazonPasses() {
        UrlValidator unrestricted = validatorWith();
        assertThatCode(() -> unrestricted.validate("https://www.amazon.com/dp/B000000000"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_blocklistDisabled_amazonPasses() {
        UrlValidator disabled = disabledValidatorWith(AMAZON_PATTERN);
        assertThatCode(() -> disabled.validate("https://www.amazon.com/dp/B000000000"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_blankPatternEntry_ignored() {
        UrlValidator withBlank = validatorWith("   ", "");
        assertThatCode(() -> withBlank.validate("https://www.amazon.com/dp/B000000000"))
                .doesNotThrowAnyException();
    }

    @Test
    void validate_uppercasePatternConfig_stillMatchesLowercaseHost() {
        UrlValidator caseInsensitive = validatorWith("(^|\\.)AMAZON\\.[A-Z]{2,3}(\\.[A-Z]{2})?$");
        assertThatThrownBy(() -> caseInsensitive.validate("https://www.amazon.com/dp/B000000000"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("amazon");
                });
    }

    @Test
    void validate_customBlocklistEntry_rejected() {
        UrlValidator custom = validatorWith("(^|\\.)example\\.com$");
        assertThatThrownBy(() -> custom.validate("https://www.example.com/foo"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("www.example.com").contains("not currently supported");
                });
    }

    // --- SSRF: resolved-IP block predicate (one per range) ---

    @ParameterizedTest
    @ValueSource(
            strings = {
                "127.0.0.1", // loopback /8
                "0.0.0.0", // any-local /8
                "0.0.0.5", // any-local /8 (proves the whole /8, not just .0.0.0)
                "169.254.0.1", // link-local
                "169.254.169.254", // AWS/GCP/Azure metadata
                "10.0.0.1", // private /8
                "172.16.0.1", // private /12 lower bound
                "172.31.255.255", // private /12 upper bound
                "192.168.1.1", // private /16
                "100.100.100.200", // CGNAT — Alibaba metadata
                "192.0.0.192", // Oracle OCI metadata
                "198.18.0.1", // benchmarking /15
                "224.0.0.1", // multicast
                "240.0.0.1", // reserved /4
                "255.255.255.255", // broadcast
                "::1", // IPv6 loopback
                "fc00::1", // ULA
                "fd00::1", // ULA
                "fe80::1", // IPv6 link-local
                "ff02::1", // IPv6 multicast
            })
    void assertHostAllowed_resolvedToInternalIp_rejected(String ip) {
        assertResolvedAddrBlocked(addr(ip));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "8.8.8.8",
                "172.15.0.1", // just below the 172.16/12 private block
                "172.32.0.1", // just above it
                "100.63.255.255", // just below CGNAT 100.64/10
                "100.128.0.0", // just above CGNAT
                "197.255.255.255", // just below 198.18 (unrelated public)
                "1.1.1.1",
            })
    void assertHostAllowed_resolvedToPublicIp_passes(String ip) {
        UrlValidator v = validatorWith(resolverReturning(addr(ip)));
        assertThatCode(() -> v.validate("https://ok.example/x")).doesNotThrowAnyException();
    }

    // --- SSRF: IPv6-embedded-IPv4 must be extracted and checked (genuine Inet6Address, not collapsed) ---

    @Test
    void ipv6MappedInternal_rejected() {
        // ::ffff:7f00:1 (127.0.0.1) as a genuine Inet6Address (getByName would collapse to Inet4).
        assertResolvedAddrBlocked(addr6(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 0x7f, 0, 0, 1));
    }

    @Test
    void ipv6SixToFourInternal_rejected() {
        // 2002:7f00:1:: embeds 127.0.0.1 at bytes 2..6.
        assertResolvedAddrBlocked(addr6(0x20, 0x02, 0x7f, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void nat64WellKnownInternal_rejected() {
        // 64:ff9b::7f00:1 (NAT64 well-known prefix embedding 127.0.0.1).
        assertResolvedAddrBlocked(addr6(0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 0x7f, 0, 0, 1));
    }

    @Test
    void nat64LocalUsePrefix_rejectedWholesale() {
        // 64:ff9b:1:: (RFC 8215 local-use NAT64) — blocked as a whole, not IPv4-extracted.
        assertResolvedAddrBlocked(addr6(0x00, 0x64, 0xff, 0x9b, 0x00, 0x01, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
    }

    @Test
    void nat64WellKnownPublic_passes() {
        // 64:ff9b::0808:0808 synthesises the public 8.8.8.8 — must pass (the DNS64/NAT64 fix).
        InetAddress a = addr6(0x00, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0, 0x08, 0x08, 0x08, 0x08);
        UrlValidator v = validatorWith(resolverReturning(a));
        assertThatCode(() -> v.validate("https://ok.example/x")).doesNotThrowAnyException();
    }

    @Test
    void ipv6SiitTranslatedInternal_rejected() {
        // ::ffff:0:127.0.0.1 (IPv4-translated / SIIT: ffff at bytes 8-9, 0000 at 10-11).
        assertResolvedAddrBlocked(addr6(0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 0, 0, 0x7f, 0, 0, 1));
    }

    @Test
    void ipv6SiitTranslatedPublic_passes() {
        // ::ffff:0:8.8.8.8 must still pass — the SIIT extraction must not over-block a public embed.
        InetAddress a = addr6(0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 0, 0, 0x08, 0x08, 0x08, 0x08);
        UrlValidator v = validatorWith(resolverReturning(a));
        assertThatCode(() -> v.validate("https://ok.example/x")).doesNotThrowAnyException();
    }

    @Test
    void ipv4MappedViaGetByName_collapsesButStillBlocked() {
        // getByName("::ffff:127.0.0.1") collapses to Inet4Address → the plain isBlockedV4 path.
        assertResolvedAddrBlocked(addr("::ffff:127.0.0.1"));
    }

    // --- SSRF: parser-differential string rejects (must NOT consult DNS) ---

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://0177.0.0.1/", // octal
                "http://010.0.0.1/", // leading-zero octet
                "http://0x7f000001/", // hex whole-host
                "http://2130706433/", // integer form (127.0.0.1)
                "http://2852039166/", // integer form (169.254.169.254)
                "http://127.0.1/", // 3 parts
                "http://127.0.0.256/", // octet > 255
                "http://127.0.0.99999999999/", // oversized octet → 400, NOT 500 (no parseInt overflow)
                "http://127.0.0.0x01/", // mixed hex octet
                "http://[::ffff:0177.0.0.1]/", // octal in an IPv6-embedded v4 tail
                "http://user@8.8.8.8/", // userinfo
                "http://localhost/", // loopback alias
                "http://api.localhost/", // *.localhost
                "http://localhost./", // trailing-dot loopback alias
                "http://0177.0.0.1./", // trailing-dot octal
                "http://example.com./", // trailing-dot reg-name (#139 review fix)
                "http://8.8.8.8./", // trailing-dot canonical quad
            })
    void validate_parserDifferentialHost_rejectedWithoutDns(String url) {
        UrlValidator v = validatorWith(FAIL_IF_CALLED);
        assertThatThrownBy(() -> v.validate(url)).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getReason()).isNotNull();
        });
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "http://sony0x.com/", // a real domain that merely contains "0x" — NOT an IP-literal shape
                "http://cafe0x.example/",
            })
    void validate_domainContainingHexLikeText_passes(String url) {
        UrlValidator v = validatorWith(PUBLIC);
        assertThatCode(() -> v.validate(url)).doesNotThrowAnyException();
    }

    @Test
    void validate_ipv6WithHexGroups_notFalselyRejected() {
        // Only an all-numeric dotted tail is canonical-checked; hex groups like 0db8 must not trip it.
        UrlValidator v = validatorWith(resolverReturning(addr("8.8.8.8")));
        assertThatCode(() -> v.validate("http://[2001:0db8::1]/")).doesNotThrowAnyException();
    }

    // --- SSRF: resolver error mapping ---

    @Test
    void resolverUnknownHost_returns400() {
        UrlValidator v = validatorWith(host -> {
            throw new UnknownHostException(host);
        });
        assertThatThrownBy(() -> v.validate("https://nope.example/x"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void resolverTimeout_returns504() {
        UrlValidator v = validatorWith(host -> {
            throw new TimeoutException("slow");
        });
        assertThatThrownBy(() -> v.validate("https://slow.example/x"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.GATEWAY_TIMEOUT));
    }

    @Test
    void resolverUnavailable_returns503() {
        UrlValidator v = validatorWith(host -> {
            throw new HostResolutionUnavailableException("saturated", null);
        });
        assertThatThrownBy(() -> v.validate("https://busy.example/x"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    }

    @Test
    void resolverReturnsEmpty_returns400() {
        UrlValidator v = validatorWith(host -> new InetAddress[0]);
        assertThatThrownBy(() -> v.validate("https://empty.example/x"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // --- SSRF: DNS rebinding / multi-record ---

    @Test
    void multiRecord_anyInternal_rejected() {
        UrlValidator v = validatorWith(resolverReturning(addr("8.8.8.8"), addr("10.0.0.1")));
        assertThatThrownBy(() -> v.validate("https://split.example/x"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // --- bracket strip + null/blank + two entry points ---

    @Test
    void bracketedIpv6Literal_resolvesDeBracketed_andBlocked() {
        // A resolver that only knows "::1" (de-bracketed) — proves the '[...]' is stripped before resolve.
        HostResolver onlyKnowsColonColonOne = host -> {
            if (!host.equals("::1")) {
                throw new UnknownHostException(host);
            }
            return new InetAddress[] {addr("::1")};
        };
        UrlValidator v = validatorWith(onlyKnowsColonColonOne);
        assertThatThrownBy(() -> v.validate("http://[::1]/"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("not allowed");
                });
    }

    @Test
    void nullOrBlankUrl_return400() {
        UrlValidator v = validatorWith(FAIL_IF_CALLED);
        for (String bad : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> v.validate(bad)).isInstanceOf(ResponseStatusException.class);
        }
    }

    // --- isUnsupportedHost predicate (the scheduler's pre-skip; DNS-free, non-throwing) ---

    @Test
    void isUnsupportedHost_blocklistedHost_true() {
        UrlValidator v = validatorWith(FAIL_IF_CALLED, AMAZON_PATTERN); // must not resolve — predicate is DNS-free
        assertThat(v.isUnsupportedHost("https://www.amazon.com/dp/x")).isTrue();
    }

    @Test
    void isUnsupportedHost_allowedHost_false() {
        UrlValidator v = validatorWith(FAIL_IF_CALLED, AMAZON_PATTERN);
        assertThat(v.isUnsupportedHost("https://www.thomann.de/x")).isFalse();
    }

    @Test
    void isUnsupportedHost_blocklistDisabled_false() {
        UrlValidator v = disabledValidatorWith(AMAZON_PATTERN);
        assertThat(v.isUnsupportedHost("https://www.amazon.com/dp/x")).isFalse();
    }

    @Test
    void isUnsupportedHost_malformedOrNullUrl_false() {
        UrlValidator v = validatorWith(FAIL_IF_CALLED, AMAZON_PATTERN);
        assertThat(v.isUnsupportedHost("not a url")).isFalse();
        assertThat(v.isUnsupportedHost("mailto:x@amazon.com")).isFalse(); // no host component
        assertThat(v.isUnsupportedHost(null)).isFalse();
    }

    @Test
    void validate_appliesUnsupportedSiteBlocklist_beforeDns() {
        // The blocklist is now enforced on every path (safety control) — and cheaply, before DNS: the
        // FAIL_IF_CALLED resolver proves a blocklisted host 400s without a lookup.
        UrlValidator v = validatorWith(FAIL_IF_CALLED, AMAZON_PATTERN);
        assertThatThrownBy(() -> v.validate("https://www.amazon.com/dp/x"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("not currently supported");
                });
    }

    // --- helpers ---

    private static void assertResolvedAddrBlocked(InetAddress resolved) {
        UrlValidator v = validatorWith(resolverReturning(resolved));
        assertThatThrownBy(() -> v.validate("https://evil.example/x"))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(e.getReason()).contains("not allowed");
                });
    }

    private static HostResolver resolverReturning(InetAddress... addrs) {
        return host -> addrs;
    }

    private static InetAddress addr(String literal) {
        try {
            return InetAddress.getByName(literal);
        } catch (UnknownHostException e) {
            throw new IllegalStateException("test literal did not parse: " + literal, e);
        }
    }

    // Builds a genuine Inet6Address from 16 raw bytes WITHOUT the IPv4-mapped collapse that
    // InetAddress.getByName / getByAddress would apply.
    private static InetAddress addr6(int... bytes) {
        byte[] b = new byte[16];
        for (int i = 0; i < 16; i++) {
            b[i] = (byte) bytes[i];
        }
        try {
            return Inet6Address.getByAddress("test6", b, 0);
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UrlValidator validatorWith(HostResolver resolver, String... patterns) {
        return new UrlValidator(props(true, patterns), resolver);
    }

    private static UrlValidator validatorWith(String... patterns) {
        return new UrlValidator(props(true, patterns), PUBLIC);
    }

    private static UrlValidator disabledValidatorWith(String... patterns) {
        return new UrlValidator(props(false, patterns), PUBLIC);
    }

    private static UrlValidationProperties props(boolean enabled, String... patterns) {
        return new UrlValidationProperties(enabled, List.of(patterns), Duration.ofSeconds(2), 8, 16);
    }

    private void assertAmazonRejected(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOfSatisfying(ResponseStatusException.class, e -> {
            assertThat(e.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.getReason()).contains("amazon").contains("not currently supported");
        });
    }
}
