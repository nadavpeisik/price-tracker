package com.np.pricehunt.backend.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HashingTest {

    // SHA-256("abc") — the canonical NIST test vector. Pins UTF-8 byte encoding + hex format.
    private static final String ABC_DIGEST = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    // Exact SHA-256 of the UTF-8 bytes of "₪200" — pins the encoding contract, so a platform-default
    // charset regression (wrong bytes → wrong digest) is caught, not just shape/casing.
    private static final String SHEKEL_DIGEST = "d4e82b6427a1cb233285ffa519f8ee5749223852ad1b3536ce3a0349b39f1a01";

    @Test
    void sha256Hex_knownVector() {
        assertThat(Hashing.sha256Hex("abc")).isEqualTo(ABC_DIGEST);
    }

    @Test
    void sha256Hex_unicodeIsUtf8Encoded() {
        assertThat(Hashing.sha256Hex("₪200")).isEqualTo(SHEKEL_DIGEST);
    }

    @Test
    void sha256Hex_null_returnsNull() {
        assertThat(Hashing.sha256Hex(null)).isNull();
    }

    @Test
    void sha256Hex_blank_returnsNull() {
        assertThat(Hashing.sha256Hex("   ")).isNull();
    }

    @Test
    void sha256HexRequired_knownVector() {
        // Same digest as sha256Hex for valid input — the variants differ only in how they reject blanks.
        assertThat(Hashing.sha256HexRequired("abc")).isEqualTo(ABC_DIGEST);
    }

    @Test
    void sha256HexRequired_unicodeIsUtf8Encoded() {
        assertThat(Hashing.sha256HexRequired("₪200")).isEqualTo(SHEKEL_DIGEST);
    }

    @Test
    void sha256HexRequired_null_throws() {
        assertThatThrownBy(() -> Hashing.sha256HexRequired(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\t\n"})
    void sha256HexRequired_blank_throws(String blank) {
        assertThatThrownBy(() -> Hashing.sha256HexRequired(blank)).isInstanceOf(IllegalArgumentException.class);
    }
}
