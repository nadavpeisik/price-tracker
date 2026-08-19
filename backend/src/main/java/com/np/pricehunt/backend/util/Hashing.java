package com.np.pricehunt.backend.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hex helper for the scrape-attempt audit (issue #131). The hashes are long-lived
 * dedup/replay keys, so the byte encoding is pinned to {@link StandardCharsets#UTF_8} rather than the
 * platform default. Two deliberate contracts: {@link #sha256Hex} returns {@code null} for null/blank
 * input, because the recorder passes {@code null} evidence for BLOCKED/STRUCTURED attempts and that
 * must store a {@code null} hash rather than throw; {@link #sha256HexRequired} never returns
 * {@code null}, so callers that build their own input can use the result without a guard.
 */
public final class Hashing {

    private Hashing() {}

    /** SHA-256 of {@code value} as lowercase hex, or {@code null} when {@code value} is null/blank. */
    public static String sha256Hex(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return digestHex(value);
    }

    /**
     * SHA-256 of {@code value} as lowercase hex, for callers whose input is non-blank by construction:
     * it throws on null/blank instead of returning {@code null}, so the result is safe to dereference.
     */
    public static String sha256HexRequired(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("sha256HexRequired needs a non-blank value");
        }
        return digestHex(value);
    }

    private static String digestHex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a JDK-mandated algorithm; absence means a broken JVM, not a recoverable state.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
