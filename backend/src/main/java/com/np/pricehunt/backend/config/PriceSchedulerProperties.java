package com.np.pricehunt.backend.config;

import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration for {@link com.np.pricehunt.backend.scheduler.PriceCheckScheduler}.
 *
 * <p>Owns the whole {@code price.scheduler.*} prefix so the keys are typed/validated in one place.
 * The defaults are exposed as compile-time {@code String} constants because the scheduler's {@code
 * @Scheduled(fixedDelayString=...)} / {@code initialDelayString=...} placeholders need a constant
 * expression and must resolve to the SAME default as this record's binding — referencing one
 * constant from both sites prevents the two defaults from drifting apart. Both the annotation
 * parser (Spring's {@code DurationFormatterUtils}) and the {@code @ConfigurationProperties} binder
 * accept the simple {@code "6h"} style, so the keys carry a readable {@link Duration} value rather
 * than a raw millis count.
 *
 * <p>{@code price.scheduler.enabled} stays on {@code @ConditionalOnProperty} (a record can't be
 * injected into an annotation that gates bean creation), but {@code fixedDelay} and {@code
 * initialDelay} are bound and validated here. {@code fixedDelay} doubles as the stale-item cutoff
 * window inside {@code refreshAll()} and must be strictly positive; {@code initialDelay} may be
 * zero (start immediately).
 */
@Validated
@ConfigurationProperties("price.scheduler")
public record PriceSchedulerProperties(
        @DefaultValue(DEFAULT_FIXED_DELAY) @DurationMin(millis = 1) Duration fixedDelay,
        @DefaultValue(DEFAULT_INITIAL_DELAY) @DurationMin(nanos = 0) Duration initialDelay) {

    /** 12 hours. Shared with {@code @Scheduled(fixedDelayString=...)} so the defaults can't diverge. */
    public static final String DEFAULT_FIXED_DELAY = "12h";

    /** 1 minute. Shared with {@code @Scheduled(initialDelayString=...)}. */
    public static final String DEFAULT_INITIAL_DELAY = "1m";
}
