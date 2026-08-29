package com.np.pricehunt.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Profile;

/**
 * Timeouts for the Spring AI Ollama client's blocking (non-streaming) {@code RestClient} path,
 * applied in {@link OllamaClientConfig}.
 *
 * <p>The read timeout must clear local LLM inference's worst case: a SNIPPET/FULLTEXT extraction
 * on a ~9B CPU model (qwen3.5:9b, num-ctx 4096) over a ~2000-char prompt can run for tens of
 * seconds — hence the 120s default, well above the scraper's 40s. A too-tight read would kill a
 * legitimately slow extraction; an unbounded one would starve the connection pool (the standing
 * project rule is that every HTTP client sets explicit timeouts).
 */
@Profile("ollama")
@ConfigurationProperties("pricehunt.ollama")
public record OllamaClientProperties(
        @DefaultValue("5s") Duration connectTimeout, @DefaultValue("120s") Duration readTimeout) {}
