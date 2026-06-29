package com.np.pricehunt.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

import com.np.pricehunt.backend.config.OllamaChatOptionsProperties;
import com.np.pricehunt.backend.config.PriceExtractionProperties;
import com.np.pricehunt.backend.config.ScrapeAuditProperties;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.ScrapeAttempt;
import com.np.pricehunt.backend.domain.ScrapeFailureCode;
import com.np.pricehunt.backend.domain.ScrapeOutcome;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import com.np.pricehunt.backend.exception.EmptyExtractionInputException;
import com.np.pricehunt.backend.exception.MalformedLlmOutputException;
import com.np.pricehunt.backend.exception.ScrapeBlockedException;
import com.np.pricehunt.backend.repository.ScrapeAttemptRepository;
import com.np.pricehunt.backend.service.ExtractionConfigFingerprint;
import com.np.pricehunt.backend.service.LlmInputResolver;
import com.np.pricehunt.backend.service.OllamaPriceExtractionService;
import com.np.pricehunt.backend.util.Hashing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScrapeAttemptRecorderTest {

    private static final Instant NOW = Instant.parse("2026-06-29T00:00:00Z");
    private static final Duration RETENTION = Duration.ofDays(90);
    private static final String SNIPPET_MODEL = "snip-model";
    private static final String FULLTEXT_MODEL = "full-model";

    @Mock
    private ScrapeAttemptRepository repository;

    private final ScrapeAuditProperties auditProps = new ScrapeAuditProperties(RETENTION, "0 15 3 * * *", 8000, false);
    private final ExtractionConfigFingerprint configFingerprint =
            new ExtractionConfigFingerprint(new OllamaChatOptionsProperties(0.0, "json", 4096));

    private ScrapeAttemptRecorder recorder;

    @BeforeEach
    void setUp() {
        // Construct here, not as a field initializer: @Mock fields are injected after field init.
        recorder = new ScrapeAttemptRecorder(
                repository,
                new LlmInputResolver(auditProps),
                configFingerprint,
                new PriceExtractionProperties(SNIPPET_MODEL, FULLTEXT_MODEL),
                auditProps,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ScrapeAttempt captureSaved() {
        ArgumentCaptor<ScrapeAttempt> captor = ArgumentCaptor.forClass(ScrapeAttempt.class);
        verify(repository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void blocked_recordsBlockedRowWithNoModelOrInput() {
        ScrapeResponse scraped = new ScrapeResponse(ExtractionSource.BLOCKED, null, null, null, "cf-managed");

        recorder.recordExtractionFailure(7L, "https://x.com/p", scraped, new ScrapeBlockedException("cf-managed"));

        ScrapeAttempt a = captureSaved();
        assertThat(a.getOutcome()).isEqualTo(ScrapeOutcome.EXTRACTION_FAILED);
        assertThat(a.getFailureCode()).isEqualTo(ScrapeFailureCode.BLOCKED);
        assertThat(a.getExtractionSource()).isEqualTo(ExtractionSource.BLOCKED);
        assertThat(a.getFailureDetail()).isEqualTo("cf-managed");
        assertThat(a.getLlmInput()).isNull();
        assertThat(a.getModelName()).isNull();
        assertThat(a.getPromptVersion()).isNull();
        assertThat(a.getExtractionConfigHash()).isNull(); // no LLM ran
        assertThat(a.getContentHash()).isNull();
        assertThat(a.getLlmInputHash()).isNull();
        assertThat(a.getTrackedItemId()).isEqualTo(7L);
        assertThat(a.getCreatedAt()).isEqualTo(NOW);
        assertThat(a.getRetentionUntil()).isEqualTo(NOW.plus(RETENTION));
    }

    @Test
    void emptyInput_recordsNullModel_evenThoughSourceIsLlmTier() {
        // EMPTY_INPUT fires before any model call → the exception's (null) context must win over the
        // nominal-by-source fallback, so no model is invented.
        ScrapeResponse scraped = new ScrapeResponse(ExtractionSource.SNIPPET, null, "abc", null, null);

        recorder.recordExtractionFailure(
                1L, "https://x.com/p", scraped, new EmptyExtractionInputException("SNIPPET", 3));

        ScrapeAttempt a = captureSaved();
        assertThat(a.getFailureCode()).isEqualTo(ScrapeFailureCode.EMPTY_INPUT);
        assertThat(a.getModelName()).isNull();
        assertThat(a.getPromptVersion()).isNull();
        assertThat(a.getLlmInput()).isEqualTo("abc");
        assertThat(a.getLlmInputHash()).isEqualTo(Hashing.sha256Hex("abc"));
    }

    @Test
    void malformedOutput_recordsExactModelFromContext() {
        ScrapeResponse scraped =
                new ScrapeResponse(ExtractionSource.FULLTEXT, null, null, "Product\n$29.99\nIn stock now", null);

        recorder.recordExtractionFailure(
                2L,
                "https://x.com/p",
                scraped,
                new MalformedLlmOutputException("heavy-model", "promptv1", new RuntimeException("bad json")));

        ScrapeAttempt a = captureSaved();
        assertThat(a.getFailureCode()).isEqualTo(ScrapeFailureCode.MALFORMED_LLM_OUTPUT);
        assertThat(a.getModelName()).isEqualTo("heavy-model");
        assertThat(a.getPromptVersion()).isEqualTo("promptv1");
        // An LLM ran → the config fingerprint is recorded.
        assertThat(a.getExtractionConfigHash()).isEqualTo(configFingerprint.getExtractionConfigHash());
    }

    @Test
    void unexpectedBugOnLlmTier_fallsBackToNominalModel() {
        ScrapeResponse scraped =
                new ScrapeResponse(ExtractionSource.SNIPPET, null, "some snippet payload here", null, null);

        recorder.recordExtractionFailure(3L, "https://x.com/p", scraped, new IllegalStateException("a bug"));

        ScrapeAttempt a = captureSaved();
        assertThat(a.getFailureCode()).isEqualTo(ScrapeFailureCode.EXTRACTION_ERROR);
        assertThat(a.getModelName()).isEqualTo(SNIPPET_MODEL);
        assertThat(a.getPromptVersion()).isEqualTo(OllamaPriceExtractionService.PROMPT_VERSION);
    }

    @Test
    void validationRejection_recordsNominalModelForLlmTier() {
        ScrapeResponse scraped =
                new ScrapeResponse(ExtractionSource.FULLTEXT, null, null, "filtered\n$400.00\nIn stock", null);

        recorder.recordValidationRejection(
                4L, "https://x.com/p", scraped, ScrapeFailureCode.DELTA_EXCEEDED, "price=400 vs prior 100");

        ScrapeAttempt a = captureSaved();
        assertThat(a.getOutcome()).isEqualTo(ScrapeOutcome.VALIDATION_REJECTED);
        assertThat(a.getFailureCode()).isEqualTo(ScrapeFailureCode.DELTA_EXCEEDED);
        assertThat(a.getModelName()).isEqualTo(FULLTEXT_MODEL);
        assertThat(a.getFailureDetail()).isEqualTo("price=400 vs prior 100");
    }

    @Test
    void contentHashDiffersFromLlmInputHash_whenFilterLinesPrunes() {
        // innerText with droppable non-price lines: content_hash (raw) must differ from llm_input_hash (filtered).
        ScrapeResponse scraped =
                new ScrapeResponse(ExtractionSource.FULLTEXT, null, null, "aaaa\nbbbb\n$29.99\ncccc\ndddd", null);

        recorder.recordExtractionFailure(5L, "https://x.com/p", scraped, new IllegalStateException("bug"));

        ScrapeAttempt a = captureSaved();
        assertThat(a.getContentHash()).isNotNull();
        assertThat(a.getLlmInputHash()).isNotNull();
        assertThat(a.getContentHash()).isNotEqualTo(a.getLlmInputHash());
    }

    @Test
    void persistsMinimizedUrl() {
        ScrapeResponse scraped = new ScrapeResponse(ExtractionSource.BLOCKED, null, null, null, "x");

        recorder.recordExtractionFailure(
                6L, "https://x.com/p?id=1&utm_source=fb#frag", scraped, new ScrapeBlockedException("x"));

        assertThat(captureSaved().getUrl()).isEqualTo("https://x.com/p?id=1");
    }
}
