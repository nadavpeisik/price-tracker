package com.np.pricehunt.backend.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

/**
 * A typed mirror of the generation options Spring AI sends to Ollama
 * ({@code spring.ai.ollama.chat.options.*}), used only to fingerprint the extraction config (issue
 * #131 — see {@code ExtractionConfigFingerprint}).
 *
 * <p>We deliberately bind these ourselves rather than introspect the autoconfigured {@code ChatModel}:
 * casting it to {@code OllamaChatModel} would break compilation the moment the hosted-LLM migration
 * (#121) swaps the bean type. The trade-off is a small drift risk — these must stay aligned with the
 * options Spring AI actually applies — which is why they are {@code @NotNull}: if a value is omitted
 * from {@code application.properties}, Spring binds {@code null} here while Spring AI would silently
 * fall back to its own default (e.g. temperature 0.7), so we fail the boot instead of hashing a value
 * that doesn't match what's sent. The model is intentionally excluded (it's a per-row column, and it
 * is overridden per call: snippet vs fulltext tier).
 */
@Profile("ollama")
@Validated
@ConfigurationProperties("spring.ai.ollama.chat.options")
public record OllamaChatOptionsProperties(
        @NotNull Double temperature, @NotBlank String format, @NotNull Integer numCtx) {}
