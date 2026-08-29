package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.dto.PriceLlmResult;
import com.np.pricehunt.backend.util.Hashing;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.stereotype.Component;

/**
 * Fingerprints the LLM <em>extraction config</em> — the structured-output JSON schema (from {@link
 * PriceLlmResult}) + the native-structured-output mode + the provider and its generation options —
 * into a short hash stored on each failure-audit row ({@code scrape_attempt.extraction_config_hash},
 * issue #131). It is a SEPARATE axis from {@code prompt_version} (prompt text) and {@code model_name}
 * (per-row): replay groups by {@code prompt_version} for "same wording" and by the full tuple
 * ({@code prompt_version}, {@code model_name}, {@code extraction_config_hash}) for "same extraction
 * conditions".
 *
 * <p>The provider id and its options are supplied by {@link ExtractionLlmProvider}, so Groq and Ollama
 * rows are never conflated even if every other input matched — which is exactly what this class was
 * built for ahead of the hosted-LLM migration (#121). Note that migration necessarily changed the hash
 * for <em>both</em> providers, since "provider" became part of the descriptor: rows written before and
 * after it are intentionally distinguishable.
 *
 * <p>A startup constant (schema + options don't vary per call — only the model does, separately), so it
 * is computed once and read by {@code ScrapeAttemptRecorder} whenever an LLM ran. Mirrors the shared-bean
 * pattern of {@link LlmInputResolver}.
 */
@Slf4j
@Component
public class ExtractionConfigFingerprint {

    private final String extractionConfigHash;
    private final String descriptor; // exposed for tests/debugging — the exact input that was hashed

    public ExtractionConfigFingerprint(ExtractionLlmProvider provider) {
        // The schema is structural (PriceLlmResult's shape + the enum members); the service's custom
        // deserialization mapper doesn't change it, so a default converter yields the same schema sent.
        String schema = new BeanOutputConverter<>(PriceLlmResult.class).getJsonSchema();
        // Length-frame the schema so text can't shift across the schema|options boundary; the provider's
        // descriptor stringifies typed numerics deterministically, so 0 renders as "0.0" stably.
        // native flag read from LlmPriceExtractionService (single source of truth) — a provider's own
        // JSON mode and native structured output are distinct controls, so both are fingerprinted.
        this.descriptor = "schema:" + schema.length() + ":" + schema
                + "|native:" + LlmPriceExtractionService.NATIVE_STRUCTURED_OUTPUT
                + "|provider:" + provider.name()
                + "|" + provider.optionsDescriptor();
        this.extractionConfigHash = Hashing.sha256HexRequired(descriptor).substring(0, 16);
        log.info("LLM extraction_config_hash={} provider={}", extractionConfigHash, provider.name());
    }

    /** Stable hash of the current extraction config; persisted on a failure row only when an LLM ran. */
    public String getExtractionConfigHash() {
        return extractionConfigHash;
    }

    /** The exact descriptor string that was hashed — for the pinning test / debugging. */
    public String getDescriptor() {
        return descriptor;
    }
}
