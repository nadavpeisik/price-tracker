package com.np.pricehunt.backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Ollama model names for {@link com.np.pricehunt.backend.service.PriceExtractionOrchestrator}'s two
 * LLM tiers.
 *
 * <p>Deliberately no {@code @DefaultValue}: a missing model name is a real misconfiguration (the
 * model must exist in the running Ollama instance), so {@code @NotBlank} fails the boot rather than
 * binding {@code null} and surfacing as an opaque LLM call failure on the first scrape. {@code
 * snippetModel} drives the SNIPPET tier (smaller/faster), {@code fulltextModel} the FULLTEXT tier
 * and the SNIPPET retry.
 */
@Validated
@ConfigurationProperties("price.extraction")
public record PriceExtractionProperties(@NotBlank String snippetModel, @NotBlank String fulltextModel) {}
