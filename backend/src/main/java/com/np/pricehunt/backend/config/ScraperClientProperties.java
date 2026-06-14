package com.np.pricehunt.backend.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration for {@link com.np.pricehunt.backend.client.ScraperClient}.
 *
 * <p>The read timeout must clear the scraper's worst case: goto (30s) + content-render wait (8s) +
 * tier processing + margin — hence the 40s default, well above the FX client's 10s.
 */
@ConfigurationProperties("scraper")
public record ScraperClientProperties(
        // localhost default is the dev/compose target; application.properties sets it explicitly.
        // A missing value in prod would fall back to localhost rather than fail fast — acceptable
        // for now; tighten with @Validated/@NotBlank only if a real misconfig risk appears.
        @DefaultValue("http://localhost:8001") String baseUrl,
        @DefaultValue("5s") Duration connectTimeout,
        @DefaultValue("40s") Duration readTimeout) {}
