package com.np.pricehunt.backend.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Policy for the failure-first scrape-attempt audit (issue #131).
 *
 * <ul>
 *   <li>{@code retention} — TTL for audit rows; the daily purge deletes anything older.
 *   <li>{@code purgeCron} — when the purge scheduler runs (UTC).
 *   <li>{@code maxLlmInputChars} — a high safety ceiling on the persisted/extracted LLM input; only
 *       trips on pathological page bloat. Validated {@code >= FALLBACK} in {@code LlmInputResolver}
 *       so it can never sever legitimate FULLTEXT (which {@code filterLines} already budgets to ~3000).
 *   <li>{@code exportEnabled} — disabled-by-default second gate (besides the {@code dev} profile) on
 *       the regression-export endpoint, which returns untrusted raw page text.
 * </ul>
 */
@Validated
@ConfigurationProperties("scrape.audit")
public record ScrapeAuditProperties(
        @DefaultValue("90d") @DurationMin(days = 1) Duration retention,
        @DefaultValue("0 15 3 * * *") @NotBlank String purgeCron,
        @DefaultValue("8000") @Positive int maxLlmInputChars,
        @DefaultValue("false") boolean exportEnabled) {}
