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
 * the per-process rate-limit window for user-initiated refreshes. All are validated so a
 * nonsensical value fails the boot rather than silently corrupting validation/rate-limiting.
 *
 * <p>{@code maxListingsPerProduct} bounds how many shop URLs one product may carry. It describes the
 * shape of a <em>product</em>, not an entitlement of whoever tracked it — a product with hundreds of
 * listings is a matching bug (the wrong product, or a discovery pass gone wrong), and that stays true
 * however many users the app has. Enforced exactly rather than softly: the count and the insert both
 * run under the parent product's write lock, so concurrent admissions to the same product serialize.
 * Writers below the service layer ({@code DevDataSeeder}) are exempt by design.
 */
@Validated
@ConfigurationProperties("price.tracking")
public record PriceTrackingProperties(
        @DefaultValue("200") @Positive int maxDeltaPercent,
        @DefaultValue("1m") @DurationMin(millis = 1) Duration minRefreshInterval,
        @DefaultValue("20") @Positive int maxListingsPerProduct) {}
