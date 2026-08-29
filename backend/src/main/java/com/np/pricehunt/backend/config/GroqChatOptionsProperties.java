package com.np.pricehunt.backend.config;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Profile;
import org.springframework.validation.annotation.Validated;

/**
 * A typed mirror of the generation options Spring AI sends to Groq
 * ({@code spring.ai.openai.chat.options.*}), used to fingerprint the extraction config (issue #131 —
 * see {@code ExtractionConfigFingerprint}) and to describe the provider in the startup log.
 *
 * <p>Same rationale as {@link OllamaChatOptionsProperties}: we bind these ourselves rather than
 * introspect the autoconfigured {@code ChatModel}, and they are {@code @NotNull} so an omitted value
 * fails the boot instead of letting us hash a value that differs from what Spring AI actually sends
 * (its own default would silently apply). The model is intentionally excluded — it is a per-row
 * column and is overridden per call (snippet vs fulltext tier).
 *
 * <p>{@code reasoningEffort} is constrained to the values Groq accepts for the gpt-oss models. A
 * typo would otherwise bind cleanly and then fail <em>every</em> extraction with an HTTP 400; the
 * pattern turns that into a boot failure naming the bad value.
 */
@Profile("!ollama")
@Validated
@ConfigurationProperties("spring.ai.openai.chat.options")
public record GroqChatOptionsProperties(
        @NotNull Double temperature, @NotNull @Pattern(regexp = "low|medium|high") String reasoningEffort) {}
