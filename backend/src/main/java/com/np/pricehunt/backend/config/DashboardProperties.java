package com.np.pricehunt.backend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the dashboard query endpoint (issue #146).
 *
 * <p>{@code maxPageSize} is a clamp rather than a rejection, mirroring {@code PriceTrendService}'s
 * window handling: an over-large request gets the largest supported page and a log line, not a 400.
 */
@Validated
@ConfigurationProperties("price.dashboard")
public record DashboardProperties(@DefaultValue("100") @Positive @Max(500) int maxPageSize) {}
