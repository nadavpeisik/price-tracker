package com.np.pricehunt.backend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for the FX-normalized product price-trend engine (issue #145).
 *
 * <p>{@code defaultWindowDays} is the sparkline window applied when a request omits {@code days};
 * {@code maxWindowDays} is the absolute ceiling a request can be clamped to, mirroring the two-year
 * clamp {@link com.np.pricehunt.backend.service.ProductQueryService} already applies to raw price
 * history.
 *
 * <p>{@code carryForwardDays} is the freshness TTL: a listing's last observed price keeps counting
 * as that listing's price for this many days after the observation, then the listing drops out until
 * it is scraped again. The dashboard's lean pass ({@code DashboardSnapshotService}) applies it through
 * the same calculator the sparkline endpoint uses, so a row and that product's trend cannot disagree
 * by construction.
 *
 * <p>The 7-day delta window itself is <em>not</em> configurable — "7-day delta" is the feature's
 * semantics, not a tuning knob, so it lives as a constant in {@code PriceTrendCalculator}.
 */
@Validated
@ConfigurationProperties("price.trend")
public record PriceTrendProperties(
        @DefaultValue("30") @Positive int defaultWindowDays,
        @DefaultValue("730") @Positive @Max(730) int maxWindowDays,
        @DefaultValue("7") @Positive @Max(90) int carryForwardDays) {

    public PriceTrendProperties {
        // Cross-field invariant: bean validation can't express it, and a default above the ceiling
        // would silently defeat the clamp. Throwing here also guards direct construction in tests.
        if (defaultWindowDays > maxWindowDays) {
            throw new IllegalArgumentException(
                    "price.trend.default-window-days (%d) must be <= price.trend.max-window-days (%d)"
                            .formatted(defaultWindowDays, maxWindowDays));
        }
    }
}
