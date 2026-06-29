package com.np.pricehunt.backend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UrlSanitizerTest {

    @Test
    void stripsFragment() {
        assertThat(UrlSanitizer.minimize("https://shop.com/item#reviews")).isEqualTo("https://shop.com/item");
    }

    @Test
    void stripsUtmParams_keepsProductParams() {
        assertThat(UrlSanitizer.minimize("https://shop.com/p?id=42&utm_source=fb&utm_campaign=x&color=red"))
                .isEqualTo("https://shop.com/p?id=42&color=red");
    }

    @Test
    void stripsKnownClickIds() {
        assertThat(UrlSanitizer.minimize("https://shop.com/p?gclid=abc&fbclid=def&sku=9"))
                .isEqualTo("https://shop.com/p?sku=9");
    }

    @Test
    void dropsQueryEntirelyWhenOnlyTrackingParams() {
        assertThat(UrlSanitizer.minimize("https://shop.com/p?utm_source=fb&fbclid=x"))
                .isEqualTo("https://shop.com/p");
    }

    @Test
    void stripsFragmentAndTrackingTogether() {
        assertThat(UrlSanitizer.minimize("https://shop.com/p?id=1&utm_medium=cpc#section"))
                .isEqualTo("https://shop.com/p?id=1");
    }

    @Test
    void stripsPercentEncodedTrackingKey() {
        // utm%5Fsource decodes to utm_source — must still be stripped, not persisted.
        assertThat(UrlSanitizer.minimize("https://shop.com/p?utm%5Fsource=fb&id=7"))
                .isEqualTo("https://shop.com/p?id=7");
    }

    @Test
    void malformedPercentEncoding_doesNotThrow_passesThrough() {
        // A malformed %ZZ can't be decoded — best-effort falls back to the literal key, never throws.
        // utm%ZZ_source isn't recognized (literal key starts with "utm%", not "utm_"), so it's kept.
        assertThat(UrlSanitizer.minimize("https://shop.com/p?utm%ZZ_source=x&sku=1"))
                .isEqualTo("https://shop.com/p?utm%ZZ_source=x&sku=1");
    }

    @Test
    void leavesCleanUrlUntouched() {
        assertThat(UrlSanitizer.minimize("https://shop.com/item?id=42")).isEqualTo("https://shop.com/item?id=42");
    }

    @Test
    void nullAndBlankPassThrough() {
        assertThat(UrlSanitizer.minimize(null)).isNull();
        assertThat(UrlSanitizer.minimize("  ")).isEqualTo("  ");
    }

    @Test
    void stripsBasicAuthUserInfo() {
        // Credentials embedded in the authority must never be persisted in the audit URL.
        assertThat(UrlSanitizer.minimize("https://user:pass@shop.com/item?id=42"))
                .isEqualTo("https://shop.com/item?id=42");
    }

    @Test
    void keepsAtSignInPath() {
        // An '@' in the path is not authority userinfo — it must survive.
        assertThat(UrlSanitizer.minimize("https://shop.com/u/@handle?ref=1"))
                .isEqualTo("https://shop.com/u/@handle?ref=1");
    }

    @Test
    void stripsUserInfo_schemeRelativeUrl() {
        // A scheme-relative "//user:pass@host/..." has no "://" — the authority still starts after "//".
        assertThat(UrlSanitizer.minimize("//user:pass@shop.com/item")).isEqualTo("//shop.com/item");
    }
}
