package com.np.pricehunt.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.validation.BindValidationException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Binds the price.* {@code @ConfigurationProperties} records from raw property values using a
 * lightweight {@link ApplicationContextRunner} — no database, web server, or Ollama. Verifies that
 * defaults apply, explicit values bind (including {@link Duration} parsing of the simple "6h"
 * style), and that {@code @Validated} constraints turn a bad value into a context-startup failure.
 */
class PricePropertiesBindingTest {

    private static BindValidationException validationFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause != null) {
            if (cause instanceof BindValidationException bve) {
                return bve;
            }
            cause = cause.getCause();
        }
        throw new AssertionError("expected a BindValidationException in the cause chain", failure);
    }

    // --- PriceHistoryProperties ---

    private final ApplicationContextRunner history =
            new ApplicationContextRunner().withUserConfiguration(HistoryConfig.class);

    @Test
    void history_defaultApplies() {
        history.run(ctx -> assertThat(ctx.getBean(PriceHistoryProperties.class).defaultWindowDays())
                .isEqualTo(90));
    }

    @Test
    void history_bindsExplicitValue() {
        history.withPropertyValues("price.history.default-window-days=30")
                .run(ctx -> assertThat(ctx.getBean(PriceHistoryProperties.class).defaultWindowDays())
                        .isEqualTo(30));
    }

    @Test
    void history_rejectsNonPositive() {
        history.withPropertyValues("price.history.default-window-days=0")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    // --- PriceExtractionProperties ---

    private final ApplicationContextRunner extraction =
            new ApplicationContextRunner().withUserConfiguration(ExtractionConfig.class);

    @Test
    void extraction_bindsModelNames() {
        extraction
                .withPropertyValues(
                        "price.extraction.snippet-model=qwen3:1.7b", "price.extraction.fulltext-model=qwen3.5:9b")
                .run(ctx -> {
                    PriceExtractionProperties props = ctx.getBean(PriceExtractionProperties.class);
                    assertThat(props.snippetModel()).isEqualTo("qwen3:1.7b");
                    assertThat(props.fulltextModel()).isEqualTo("qwen3.5:9b");
                });
    }

    @Test
    void extraction_rejectsBlankModelName() {
        extraction
                .withPropertyValues("price.extraction.snippet-model=", "price.extraction.fulltext-model=qwen3.5:9b")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    // --- PriceSchedulerProperties ---

    private final ApplicationContextRunner scheduler =
            new ApplicationContextRunner().withUserConfiguration(SchedulerConfig.class);

    @Test
    void scheduler_defaultsParseToDurations() {
        scheduler.run(ctx -> {
            PriceSchedulerProperties props = ctx.getBean(PriceSchedulerProperties.class);
            assertThat(props.fixedDelay()).isEqualTo(Duration.ofHours(6));
            assertThat(props.initialDelay()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    void scheduler_bindsSimpleDurationStyle() {
        scheduler.withPropertyValues("price.scheduler.fixed-delay=90m").run(ctx -> assertThat(
                        ctx.getBean(PriceSchedulerProperties.class).fixedDelay())
                .isEqualTo(Duration.ofMinutes(90)));
    }

    @Test
    void scheduler_rejectsNonPositiveFixedDelay() {
        scheduler.withPropertyValues("price.scheduler.fixed-delay=0s").run(ctx -> assertThat(
                        validationFailure(ctx.getStartupFailure()))
                .isNotNull());
    }

    @Test
    void scheduler_allowsZeroInitialDelay() {
        scheduler.withPropertyValues("price.scheduler.initial-delay=0s").run(ctx -> assertThat(
                        ctx.getBean(PriceSchedulerProperties.class).initialDelay())
                .isZero());
    }

    // --- PriceTrackingProperties ---

    private final ApplicationContextRunner tracking =
            new ApplicationContextRunner().withUserConfiguration(TrackingConfig.class);

    @Test
    void tracking_defaultsApply() {
        tracking.run(ctx -> {
            PriceTrackingProperties props = ctx.getBean(PriceTrackingProperties.class);
            assertThat(props.maxDeltaPercent()).isEqualTo(200);
            assertThat(props.minRefreshInterval()).isEqualTo(Duration.ofMinutes(1));
        });
    }

    @Test
    void tracking_rejectsNonPositiveDelta() {
        tracking.withPropertyValues("price.tracking.max-delta-percent=0")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    @EnableConfigurationProperties(PriceHistoryProperties.class)
    static class HistoryConfig {}

    @EnableConfigurationProperties(PriceExtractionProperties.class)
    static class ExtractionConfig {}

    @EnableConfigurationProperties(PriceSchedulerProperties.class)
    static class SchedulerConfig {}

    @EnableConfigurationProperties(PriceTrackingProperties.class)
    static class TrackingConfig {}
}
