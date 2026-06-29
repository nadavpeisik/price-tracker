package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.config.OllamaChatOptionsProperties;
import org.junit.jupiter.api.Test;

class ExtractionConfigFingerprintTest {

    private static ExtractionConfigFingerprint fingerprint(double temperature, String format, int numCtx) {
        return new ExtractionConfigFingerprint(new OllamaChatOptionsProperties(temperature, format, numCtx));
    }

    @Test
    void hash_is16LowercaseHexChars() {
        assertThat(fingerprint(0.0, "json", 4096).getExtractionConfigHash())
                .hasSize(16)
                .matches("[0-9a-f]{16}");
    }

    @Test
    void hash_isDeterministicForSameConfig() {
        assertThat(fingerprint(0.0, "json", 4096).getExtractionConfigHash())
                .isEqualTo(fingerprint(0.0, "json", 4096).getExtractionConfigHash());
    }

    @Test
    void hash_changesWhenAnOptionChanges() {
        String base = fingerprint(0.0, "json", 4096).getExtractionConfigHash();
        assertThat(fingerprint(0.5, "json", 4096).getExtractionConfigHash()).isNotEqualTo(base); // temperature
        assertThat(fingerprint(0.0, "json", 8192).getExtractionConfigHash()).isNotEqualTo(base); // num-ctx
        assertThat(fingerprint(0.0, "text", 4096).getExtractionConfigHash()).isNotEqualTo(base); // format
    }

    @Test
    void descriptor_includesSchemaAndOptions() {
        // The descriptor (the exact hashed input) must capture the schema + each option, so a change to
        // any of them moves the hash. Pins the contract so a reviewer notices an intentional change.
        String descriptor = fingerprint(0.0, "json", 4096).getDescriptor();
        assertThat(descriptor)
                .contains("schema:")
                .contains("native:true")
                .contains("temperature:0.0")
                .contains("numCtx:4096")
                .contains("format:json")
                .contains("availability"); // a PriceLlmResult field name appears in the embedded JSON schema
    }
}
