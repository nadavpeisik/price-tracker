package com.np.pricehunt.backend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class DomainNormalizerTest {

    // --- registrableDomain ---

    @Test
    void collapsesWwwAndSubdomains() {
        assertThat(DomainNormalizer.registrableDomain("https://www.amazon.com/dp/1"))
                .isEqualTo("amazon.com");
        assertThat(DomainNormalizer.registrableDomain("https://m.ebay.com/itm/1"))
                .isEqualTo("ebay.com");
        assertThat(DomainNormalizer.registrableDomain("https://shop.super-pharm.co.il/p/1"))
                .isEqualTo("super-pharm.co.il");
    }

    @Test
    void honoursMultiLevelPublicSuffix() {
        // .co.il is a public suffix, so the registrable domain keeps three labels.
        assertThat(DomainNormalizer.registrableDomain("https://www.string6.co.il/product/x"))
                .isEqualTo("string6.co.il");
    }

    @Test
    void lowercasesHost() {
        assertThat(DomainNormalizer.registrableDomain("https://WWW.Example.COM/x"))
                .isEqualTo("example.com");
    }

    @Test
    void punycodeHostIsKeptAscii() {
        // Already-punycode IDN host stays ASCII (the stable mapping key).
        assertThat(DomainNormalizer.registrableDomain("https://www.xn--bcher-kva.de/x"))
                .isEqualTo("xn--bcher-kva.de");
    }

    @Test
    void ipAndLocalhostReturnedAsIs() {
        assertThat(DomainNormalizer.registrableDomain("http://127.0.0.1:8080/x"))
                .isEqualTo("127.0.0.1");
        assertThat(DomainNormalizer.registrableDomain("http://localhost:8080/x"))
                .isEqualTo("localhost");
        assertThat(DomainNormalizer.registrableDomain("http://[2001:db8::1]:8080/x"))
                .isEqualTo("[2001:db8::1]");
    }

    @Test
    void unparseableUrlYieldsNull() {
        assertThat(DomainNormalizer.registrableDomain("not a url")).isNull();
    }

    // --- prettyLabel ---

    @Test
    void prettyLabelTitleCasesFirstLabel() {
        assertThat(DomainNormalizer.prettyLabel("super-pharm.co.il")).isEqualTo("Super Pharm");
        assertThat(DomainNormalizer.prettyLabel("amazon.com")).isEqualTo("Amazon");
    }

    @Test
    void prettyLabelUnpunycodesIdn() {
        assertThat(DomainNormalizer.prettyLabel("xn--bcher-kva.de")).isEqualTo("Bücher");
    }

    @Test
    void prettyLabelLeavesIpUntouched() {
        assertThat(DomainNormalizer.prettyLabel("127.0.0.1")).isEqualTo("127.0.0.1");
    }

    @Test
    void prettyLabelNullSafe() {
        assertThat(DomainNormalizer.prettyLabel(null)).isNull();
    }
}
