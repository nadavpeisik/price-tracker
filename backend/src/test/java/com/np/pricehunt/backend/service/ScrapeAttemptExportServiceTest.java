package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ScrapeAttemptExportServiceTest {

    @Mock
    private ScrapeAttemptRepository repository;

    private static ScrapeAttempt attempt(Long id, ScrapeFailureCode code, String llmInput) {
        return ScrapeAttempt.builder()
                .id(id)
                .failureCode(code)
                .extractionSource(ExtractionSource.SNIPPET)
                .llmInput(llmInput)
                .build();
    }

    @Test
    void draftFor_returnsDraftForAttemptWithLlmInput() {
        when(repository.findById(5L))
                .thenReturn(Optional.of(attempt(5L, ScrapeFailureCode.MALFORMED_LLM_OUTPUT, "$10 in stock")));

        ScrapeAttemptExportService.FixtureDraft draft = new ScrapeAttemptExportService(repository).draftFor(5L);

        assertThat(draft.name()).isEqualTo("malformed_llm_output_5");
        assertThat(draft.text()).isEqualTo("$10 in stock");
        assertThat(draft.expectedAvailability()).isNull(); // a human labels this before committing
    }

    @Test
    void draftFor_missingAttempt_404() {
        when(repository.findById(9L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new ScrapeAttemptExportService(repository).draftFor(9L))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void draftFor_attemptWithoutLlmInput_400() {
        when(repository.findById(3L)).thenReturn(Optional.of(attempt(3L, ScrapeFailureCode.BLOCKED, null)));

        assertThatThrownBy(() -> new ScrapeAttemptExportService(repository).draftFor(3L))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> assertThat(e.getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
    }
}
