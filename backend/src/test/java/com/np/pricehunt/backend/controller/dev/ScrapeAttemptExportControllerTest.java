package com.np.pricehunt.backend.controller.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.np.pricehunt.backend.service.ScrapeAttemptExportService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The endpoint's lookup/draft logic lives in {@link ScrapeAttemptExportService} (tested there). This
 * class pins only the security-critical gating: the controller bean is absent unless BOTH the {@code
 * dev} profile AND {@code scrape.audit.export-enabled=true} are present.
 */
class ScrapeAttemptExportControllerTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ScrapeAttemptExportService.class, () -> mock(ScrapeAttemptExportService.class))
            .withUserConfiguration(ScrapeAttemptExportController.class);

    @Test
    void absentByDefault() {
        runner.run(ctx -> assertThat(ctx).doesNotHaveBean(ScrapeAttemptExportController.class));
    }

    @Test
    void absentInDevWithoutExportEnabled() {
        runner.withInitializer(c -> c.getEnvironment().setActiveProfiles("dev"))
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ScrapeAttemptExportController.class));
    }

    @Test
    void absentWithExportEnabledButNotDevProfile() {
        runner.withPropertyValues("scrape.audit.export-enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ScrapeAttemptExportController.class));
    }

    @Test
    void presentWithBothGates() {
        runner.withInitializer(c -> c.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues("scrape.audit.export-enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(ScrapeAttemptExportController.class));
    }
}
