package com.np.pricehunt.backend.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for {@link com.np.pricehunt.backend.service.ProductQueryService}'s price-history
 * queries.
 *
 * <p>{@code defaultWindowDays} is the look-back window applied when a history request omits an
 * explicit {@code from} bound. {@code @Positive} makes a zero/negative misconfiguration a
 * fail-fast startup error rather than a query that silently returns nothing.
 */
@Validated
@ConfigurationProperties("price.history")
public record PriceHistoryProperties(@DefaultValue("90") @Positive int defaultWindowDays) {}
