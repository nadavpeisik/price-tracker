package com.np.pricehunt.backend.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * URL-validation policy.
 *
 * <ul>
 *   <li>{@code unsupportedSitesEnabled} / {@code unsupportedHostPatterns} — the UX "unsupported sites"
 *       blocklist (a product decision, applied only on user input). Unrelated to SSRF.
 *   <li>{@code dnsResolveTimeout} / {@code dnsResolverPoolSize} / {@code dnsResolverQueueCapacity} — the
 *       bounded, timeout-guarded DNS bulkhead behind the SSRF host check (#139); see
 *       {@code SystemHostResolver}. The SSRF check itself is unconditional (no toggle).
 * </ul>
 */
@Validated
@ConfigurationProperties("price.validation")
public record UrlValidationProperties(
        @DefaultValue("true") boolean unsupportedSitesEnabled,
        List<String> unsupportedHostPatterns,
        // @NotNull is required: @DurationMin treats null as valid, so an explicitly-empty value would
        // slip through and NPE at toMillis(). @DurationMin(millis=1) — the resolver uses toMillis(),
        // which truncates a sub-millisecond value to 0 (an instant timeout), so the floor must be 1ms.
        @DefaultValue("2s") @NotNull @DurationMin(millis = 1) Duration dnsResolveTimeout,
        // @Max guards an operator typo from allocating a huge pool/queue at start-up.
        @DefaultValue("8") @Positive @Max(64) int dnsResolverPoolSize,
        @DefaultValue("16") @Positive @Max(1024) int dnsResolverQueueCapacity) {
    public UrlValidationProperties {
        unsupportedHostPatterns = unsupportedHostPatterns == null ? List.of() : List.copyOf(unsupportedHostPatterns);
    }
}
