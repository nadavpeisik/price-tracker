package com.np.pricehunt.backend.config;

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
        @DefaultValue("http://localhost:8001") String baseUrl,
        @DefaultValue("5000") long connectTimeoutMs,
        @DefaultValue("40000") long readTimeoutMs) {}
