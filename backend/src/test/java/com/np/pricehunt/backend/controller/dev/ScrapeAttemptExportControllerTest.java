package com.np.pricehunt.backend.controller.dev;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.ScrapeAttempt;
import com.np.pricehunt.backend.domain.ScrapeFailureCode;
import com.np.pricehunt.backend.repository.ScrapeAttemptRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ScrapeAttemptExportControllerTest {

    @Mock
    private ScrapeAttemptRepository repository;

    // --- endpoint logic (direct instantiation; the gates are Spring's concern, tested below) ---

    private static ScrapeAttempt attempt(Long id, ScrapeFailureCode code, String llmInput) {
        return ScrapeAttempt.builder()
                .id(id)
                .failureCode(code)
                .extractionSource(ExtractionSource.SNIPPET)
                .llmInput(llmInput)
                .build();
    }

    @Test
    void fixture_returnsDraftForAttemptWithLlmInput() {
        when(repository.findById(5L))
                .thenReturn(Optional.of(attempt(5L, ScrapeFailureCode.MALFORMED_LLM_OUTPUT, "$10 in stock")));

        ScrapeAttemptExportController.FixtureDraft draft = new ScrapeAttemptExportController(repository).fixture(5L);

        assertThat(draft.name()).isEqualTo("malformed_llm_output_5");
        assertThat(draft.text()).isEqualTo("$10 in stock");
        assertThat(draft.expectedAvailability()).isNull(); // a human labels this before committing
    }

    @Test
    void fixture_missingAttempt_404() {
        when(repository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new ScrapeAttemptExportController(repository).fixture(9L))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void fixture_attemptWithoutLlmInput_400() {
        when(repository.findById(3L)).thenReturn(Optional.of(attempt(3L, ScrapeFailureCode.BLOCKED, null)));

        assertThatThrownBy(() -> new ScrapeAttemptExportController(repository).fixture(3L))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // --- gating: absent unless BOTH the dev profile AND scrape.audit.export-enabled=true ---

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(ScrapeAttemptRepository.class, () -> mock(ScrapeAttemptRepository.class))
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
