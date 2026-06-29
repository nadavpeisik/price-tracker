package com.np.pricehunt.backend.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * SHA-256 hex helper for the scrape-attempt audit (issue #131). The hashes are long-lived
 * dedup/replay keys, so the byte encoding is pinned to {@link StandardCharsets#UTF_8} rather than the
 * platform default. Null/blank-safe by design: the recorder passes {@code null} evidence for
 * BLOCKED/STRUCTURED attempts (no raw text), and that must produce a {@code null} hash, not an NPE.
 */
public final class Hashing {

    private Hashing() {}

    /** SHA-256 of {@code value} as lowercase hex, or {@code null} when {@code value} is null/blank. */
    public static String sha256Hex(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
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
