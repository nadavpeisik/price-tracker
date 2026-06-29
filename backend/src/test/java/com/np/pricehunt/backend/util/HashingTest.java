package com.np.pricehunt.backend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class HashingTest {

    @Test
    void sha256Hex_knownVector() {
        // SHA-256("abc") — the canonical NIST test vector. Pins UTF-8 byte encoding + hex format.
        assertThat(Hashing.sha256Hex("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void sha256Hex_unicodeIsUtf8Encoded() {
        // SHA-256 of the UTF-8 bytes of "₪200" — guards against a platform-default-charset regression.
        assertThat(Hashing.sha256Hex("₪200")).hasSize(64).isLowerCase();
    }

    @Test
    void sha256Hex_null_returnsNull() {
        assertThat(Hashing.sha256Hex(null)).isNull();
    }

    @Test
    void sha256Hex_blank_returnsNull() {
        assertThat(Hashing.sha256Hex("   ")).isNull();
    }
}
