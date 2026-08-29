package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.config.GroqChatOptionsProperties;
import com.np.pricehunt.backend.config.GroqExtractionLlmProvider;
import com.np.pricehunt.backend.config.OllamaChatOptionsProperties;
import com.np.pricehunt.backend.config.OllamaExtractionLlmProvider;
import org.junit.jupiter.api.Test;

class ExtractionConfigFingerprintTest {

    private static ExtractionConfigFingerprint groq(double temperature, String reasoningEffort) {
        return new ExtractionConfigFingerprint(
                new GroqExtractionLlmProvider(new GroqChatOptionsProperties(temperature, reasoningEffort)));
    }

    private static ExtractionConfigFingerprint ollama(double temperature, String format, int numCtx) {
        return new ExtractionConfigFingerprint(
                new OllamaExtractionLlmProvider(new OllamaChatOptionsProperties(temperature, format, numCtx)));
    }

    @Test
    void hash_is16LowercaseHexChars() {
        assertThat(groq(0.0, "low").getExtractionConfigHash()).hasSize(16).matches("[0-9a-f]{16}");
    }

    @Test
    void hash_isDeterministicForSameConfig() {
        assertThat(groq(0.0, "low").getExtractionConfigHash())
                .isEqualTo(groq(0.0, "low").getExtractionConfigHash());
    }

    @Test
    void hash_changesWhenAGroqOptionChanges() {
        String base = groq(0.0, "low").getExtractionConfigHash();
        assertThat(groq(0.5, "low").getExtractionConfigHash()).isNotEqualTo(base); // temperature
        assertThat(groq(0.0, "high").getExtractionConfigHash()).isNotEqualTo(base); // reasoning effort
    }

    @Test
    void hash_changesWhenAnOllamaOptionChanges() {
        String base = ollama(0.0, "json", 4096).getExtractionConfigHash();
        assertThat(ollama(0.5, "json", 4096).getExtractionConfigHash()).isNotEqualTo(base); // temperature
        assertThat(ollama(0.0, "json", 8192).getExtractionConfigHash()).isNotEqualTo(base); // num-ctx
        assertThat(ollama(0.0, "text", 4096).getExtractionConfigHash()).isNotEqualTo(base); // format
    }

    @Test
    void hash_differsAcrossProviders() {
        // The whole point of putting the provider in the descriptor (#121): audit rows written against
        // Groq must never be grouped with Ollama rows, even when everything else about the config lines up.
        assertThat(groq(0.0, "low").getExtractionConfigHash())
                .isNotEqualTo(ollama(0.0, "json", 4096).getExtractionConfigHash());
    }

    @Test
    void descriptor_includesSchemaProviderAndOptions() {
        // The descriptor (the exact hashed input) must capture the schema, the provider and each option,
        // so a change to any of them moves the hash. Pins the contract so a reviewer notices an
        // intentional change.
        assertThat(groq(0.0, "low").getDescriptor())
                .contains("schema:")
                .contains("native:true")
                .contains("provider:groq")
                .contains("temperature:0.0")
                .contains("reasoningEffort:low")
                .contains("availability"); // a PriceLlmResult field name appears in the embedded JSON schema

        assertThat(ollama(0.0, "json", 4096).getDescriptor())
                .contains("provider:ollama")
                .contains("temperature:0.0")
                .contains("numCtx:4096")
                .contains("format:json");
    }
}
