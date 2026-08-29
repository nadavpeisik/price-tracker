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
            assertThat(props.fixedDelay()).isEqualTo(Duration.ofHours(12));
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
            assertThat(props.maxListingsPerProduct()).isEqualTo(20);
        });
    }

    @Test
    void tracking_bindsExplicitListingsCap() {
        tracking.withPropertyValues("price.tracking.max-listings-per-product=3").run(ctx -> assertThat(
                        ctx.getBean(PriceTrackingProperties.class).maxListingsPerProduct())
                .isEqualTo(3));
    }

    @Test
    void tracking_rejectsNonPositiveListingsCap() {
        tracking.withPropertyValues("price.tracking.max-listings-per-product=0")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    @Test
    void tracking_rejectsNonPositiveDelta() {
        tracking.withPropertyValues("price.tracking.max-delta-percent=0")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    // --- ScrapeAuditProperties ---

    private final ApplicationContextRunner audit =
            new ApplicationContextRunner().withUserConfiguration(AuditConfig.class);

    @Test
    void audit_defaultsApply() {
        audit.run(ctx -> {
            ScrapeAuditProperties props = ctx.getBean(ScrapeAuditProperties.class);
            assertThat(props.retention()).isEqualTo(Duration.ofDays(90));
            assertThat(props.maxLlmInputChars()).isEqualTo(8000);
            assertThat(props.exportEnabled()).isFalse();
            assertThat(props.purgeCron()).isEqualTo("0 15 3 * * *");
        });
    }

    @Test
    void audit_bindsExplicitValues() {
        audit.withPropertyValues(
                        "scrape.audit.retention=30d",
                        "scrape.audit.max-llm-input-chars=12000",
                        "scrape.audit.export-enabled=true")
                .run(ctx -> {
                    ScrapeAuditProperties props = ctx.getBean(ScrapeAuditProperties.class);
                    assertThat(props.retention()).isEqualTo(Duration.ofDays(30));
                    assertThat(props.maxLlmInputChars()).isEqualTo(12000);
                    assertThat(props.exportEnabled()).isTrue();
                });
    }

    @Test
    void audit_rejectsRetentionBelowOneDay() {
        audit.withPropertyValues("scrape.audit.retention=12h")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    @Test
    void audit_rejectsNonPositiveMaxLlmInputChars() {
        audit.withPropertyValues("scrape.audit.max-llm-input-chars=0")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    // --- UrlValidationProperties (SSRF DNS bulkhead knobs, #139) ---

    private final ApplicationContextRunner urlValidation =
            new ApplicationContextRunner().withUserConfiguration(UrlValidationConfig.class);

    @Test
    void urlValidation_defaultsApply() {
        urlValidation.run(ctx -> {
            UrlValidationProperties props = ctx.getBean(UrlValidationProperties.class);
            assertThat(props.dnsResolveTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(props.dnsResolverPoolSize()).isEqualTo(8);
            assertThat(props.dnsResolverQueueCapacity()).isEqualTo(16);
            assertThat(props.unsupportedSitesEnabled()).isTrue();
        });
    }

    @Test
    void urlValidation_bindsExplicitValues() {
        urlValidation
                .withPropertyValues(
                        "price.validation.dns-resolve-timeout=500ms",
                        "price.validation.dns-resolver-pool-size=4",
                        "price.validation.dns-resolver-queue-capacity=32")
                .run(ctx -> {
                    UrlValidationProperties props = ctx.getBean(UrlValidationProperties.class);
                    assertThat(props.dnsResolveTimeout()).isEqualTo(Duration.ofMillis(500));
                    assertThat(props.dnsResolverPoolSize()).isEqualTo(4);
                    assertThat(props.dnsResolverQueueCapacity()).isEqualTo(32);
                });
    }

    @Test
    void urlValidation_rejectsNonPositivePoolSize() {
        urlValidation
                .withPropertyValues("price.validation.dns-resolver-pool-size=0")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    @Test
    void urlValidation_rejectsSubMillisecondTimeout() {
        // @DurationMin(millis=1): 0s truncates to 0ms at toMillis() (an instant timeout) — must be rejected.
        urlValidation
                .withPropertyValues("price.validation.dns-resolve-timeout=0s")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    @Test
    void urlValidation_rejectsPoolSizeAboveMax() {
        urlValidation
                .withPropertyValues("price.validation.dns-resolver-pool-size=100")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    @Test
    void urlValidation_rejectsQueueCapacityAboveMax() {
        urlValidation
                .withPropertyValues("price.validation.dns-resolver-queue-capacity=2000")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    // --- OllamaChatOptionsProperties (the extraction-config-fingerprint mirror) ---

    private final ApplicationContextRunner ollamaOptions =
            new ApplicationContextRunner().withUserConfiguration(OllamaOptionsConfig.class);

    @Test
    void ollamaOptions_bindsExplicitValues() {
        ollamaOptions
                .withPropertyValues(
                        "spring.ai.ollama.chat.options.temperature=0",
                        "spring.ai.ollama.chat.options.format=json",
                        "spring.ai.ollama.chat.options.num-ctx=4096")
                .run(ctx -> {
                    OllamaChatOptionsProperties props = ctx.getBean(OllamaChatOptionsProperties.class);
                    assertThat(props.temperature()).isEqualTo(0.0);
                    assertThat(props.format()).isEqualTo("json");
                    assertThat(props.numCtx()).isEqualTo(4096);
                });
    }

    @Test
    void ollamaOptions_rejectsMissingTemperature() {
        // @NotNull guards against Spring binding null while Spring AI silently falls back to its default.
        ollamaOptions
                .withPropertyValues(
                        "spring.ai.ollama.chat.options.format=json", "spring.ai.ollama.chat.options.num-ctx=4096")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    // --- GroqChatOptionsProperties / GroqClientProperties (the default provider since #121) ---

    private final ApplicationContextRunner groqOptions =
            new ApplicationContextRunner().withUserConfiguration(GroqOptionsConfig.class);

    @Test
    void groqOptions_bindsExplicitValues() {
        groqOptions
                .withPropertyValues(
                        "spring.ai.openai.chat.options.temperature=0",
                        "spring.ai.openai.chat.options.reasoning-effort=low")
                .run(ctx -> {
                    GroqChatOptionsProperties props = ctx.getBean(GroqChatOptionsProperties.class);
                    assertThat(props.temperature()).isEqualTo(0.0);
                    assertThat(props.reasoningEffort()).isEqualTo("low");
                });
    }

    @Test
    void groqOptions_rejectsMissingTemperature() {
        // @NotNull guards against Spring binding null while Spring AI silently falls back to its default.
        groqOptions
                .withPropertyValues("spring.ai.openai.chat.options.reasoning-effort=low")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    @Test
    void groqOptions_rejectsUnsupportedReasoningEffort() {
        // Groq accepts only low|medium|high for the gpt-oss models. Without the @Pattern a typo would
        // bind cleanly and then fail every single extraction with an HTTP 400.
        groqOptions
                .withPropertyValues(
                        "spring.ai.openai.chat.options.temperature=0",
                        "spring.ai.openai.chat.options.reasoning-effort=ludicrous")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    @Test
    void groqClient_appliesTimeoutDefaults() {
        new ApplicationContextRunner()
                .withUserConfiguration(GroqClientConfig.class)
                .run(ctx -> {
                    GroqClientProperties props = ctx.getBean(GroqClientProperties.class);
                    assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
                    assertThat(props.readTimeout()).isEqualTo(Duration.ofSeconds(30));
                });
    }

    @Test
    void groqClient_bindsExplicitTimeouts() {
        new ApplicationContextRunner()
                .withUserConfiguration(GroqClientConfig.class)
                .withPropertyValues("pricehunt.groq.connect-timeout=2s", "pricehunt.groq.read-timeout=45s")
                .run(ctx -> {
                    GroqClientProperties props = ctx.getBean(GroqClientProperties.class);
                    assertThat(props.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
                    assertThat(props.readTimeout()).isEqualTo(Duration.ofSeconds(45));
                });
    }

    // --- PriceTrendProperties (#145 price-trend engine) ---

    private final ApplicationContextRunner trend =
            new ApplicationContextRunner().withUserConfiguration(TrendConfig.class);

    @Test
    void trend_defaultsApply() {
        trend.run(ctx -> {
            PriceTrendProperties props = ctx.getBean(PriceTrendProperties.class);
            assertThat(props.defaultWindowDays()).isEqualTo(30);
            assertThat(props.maxWindowDays()).isEqualTo(730);
            assertThat(props.carryForwardDays()).isEqualTo(7);
        });
    }

    @Test
    void trend_bindsExplicitValues() {
        trend.withPropertyValues(
                        "price.trend.default-window-days=14",
                        "price.trend.max-window-days=365",
                        "price.trend.carry-forward-days=3")
                .run(ctx -> {
                    PriceTrendProperties props = ctx.getBean(PriceTrendProperties.class);
                    assertThat(props.defaultWindowDays()).isEqualTo(14);
                    assertThat(props.maxWindowDays()).isEqualTo(365);
                    assertThat(props.carryForwardDays()).isEqualTo(3);
                });
    }

    @Test
    void trend_rejectsNonPositiveWindow() {
        trend.withPropertyValues("price.trend.default-window-days=0")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    @Test
    void trend_rejectsCarryForwardAboveMax() {
        trend.withPropertyValues("price.trend.carry-forward-days=91")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    @Test
    void trend_rejectsMaxWindowAboveTwoYears() {
        trend.withPropertyValues("price.trend.max-window-days=1000")
                .run(ctx ->
                        assertThat(validationFailure(ctx.getStartupFailure())).isNotNull());
    }

    @Test
    void trend_rejectsDefaultWindowAboveMaxWindow() {
        // Cross-field invariant lives in the compact constructor, so this surfaces as an
        // IllegalArgumentException rather than a bean-validation failure.
        trend.withPropertyValues("price.trend.default-window-days=100", "price.trend.max-window-days=30")
                .run(ctx -> assertThat(ctx.getStartupFailure())
                        .isNotNull()
                        .hasStackTraceContaining("must be <= price.trend.max-window-days"));
    }

    @Test
    void trend_rejectsDefaultWindowAboveMaxWindowOnDirectConstruction() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PriceTrendProperties(100, 30, 7))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- DashboardProperties (#146 dashboard query endpoint) ---

    private final ApplicationContextRunner dashboard =
            new ApplicationContextRunner().withUserConfiguration(DashboardConfig.class);

    @Test
    void dashboard_defaultApplies() {
        dashboard.run(ctx ->
                assertThat(ctx.getBean(DashboardProperties.class).maxPageSize()).isEqualTo(100));
    }

    @Test
    void dashboard_bindsExplicitValue() {
        dashboard.withPropertyValues("price.dashboard.max-page-size=25").run(ctx -> assertThat(
                        ctx.getBean(DashboardProperties.class).maxPageSize())
                .isEqualTo(25));
    }

    @Test
    void dashboard_rejectsNonPositivePageSize() {
        dashboard.withPropertyValues("price.dashboard.max-page-size=0").run(ctx -> assertThat(
                        validationFailure(ctx.getStartupFailure()))
                .isNotNull());
    }

    @Test
    void dashboard_rejectsPageSizeAboveMax() {
        dashboard.withPropertyValues("price.dashboard.max-page-size=501").run(ctx -> assertThat(
                        validationFailure(ctx.getStartupFailure()))
                .isNotNull());
    }

    @EnableConfigurationProperties(DashboardProperties.class)
    static class DashboardConfig {}

    @EnableConfigurationProperties(PriceHistoryProperties.class)
    static class HistoryConfig {}

    @EnableConfigurationProperties(PriceTrendProperties.class)
    static class TrendConfig {}

    @EnableConfigurationProperties(ScrapeAuditProperties.class)
    static class AuditConfig {}

    @EnableConfigurationProperties(OllamaChatOptionsProperties.class)
    static class OllamaOptionsConfig {}

    @EnableConfigurationProperties(GroqChatOptionsProperties.class)
    static class GroqOptionsConfig {}

    @EnableConfigurationProperties(GroqClientProperties.class)
    static class GroqClientConfig {}

    @EnableConfigurationProperties(PriceExtractionProperties.class)
    static class ExtractionConfig {}

    @EnableConfigurationProperties(PriceSchedulerProperties.class)
    static class SchedulerConfig {}

    @EnableConfigurationProperties(PriceTrackingProperties.class)
    static class TrackingConfig {}

    @EnableConfigurationProperties(UrlValidationProperties.class)
    static class UrlValidationConfig {}
}
