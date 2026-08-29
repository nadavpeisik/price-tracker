package com.np.pricehunt.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Profile;

/**
 * Timeouts for the Groq client's blocking (non-streaming) {@code RestClient} path, applied in
 * {@link GroqLlmConfig}.
 *
 * <p>Sized for a hosted LPU rather than local inference: Groq serves the gpt-oss models at roughly
 * 500–1000 tokens/sec and our extraction outputs are ~50 tokens, so 30s is already a wide ceiling —
 * the opposite end from {@link OllamaClientProperties}'s 120s, which exists to tolerate slow CPU
 * inference. Keeping it tight also bounds the worst case of the synchronous {@code POST /track}
 * path, where a tier can retry and then escalate to the heavier model.
 */
@Profile("!ollama")
@ConfigurationProperties("pricehunt.groq")
public record GroqClientProperties(
        @DefaultValue("5s") Duration connectTimeout, @DefaultValue("30s") Duration readTimeout) {}
