package com.np.pricehunt.backend.config;

import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Per-item tracking policy for {@link com.np.pricehunt.backend.service.ProductTrackingService}.
 *
 * <p>Groups the two settings that govern a tracked item's price updates under one cohesive prefix —
 * previously {@code max-delta-percent} sat on {@code price.validation.*} (already owned by {@code
 * UrlValidationProperties}'s host blocklist) and {@code min-refresh-interval} on {@code
 * price.refresh.*}, an ownership split that made neither prefix's owner obvious.
 *
 * <p>{@code maxDeltaPercent} caps how far a newly-scraped price may move from the previous one (in
 * the same currency) before the record is rejected as implausible. {@code minRefreshInterval} is
 * the per-process rate-limit window for user-initiated refreshes. Both are validated so a
 * nonsensical value fails the boot rather than silently corrupting validation/rate-limiting.
 */
@Validated
@ConfigurationProperties("price.tracking")
public record PriceTrackingProperties(
        @DefaultValue("200") @Positive int maxDeltaPercent,
        @DefaultValue("1m") @DurationMin(millis = 1) Duration minRefreshInterval) {}
