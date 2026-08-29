package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.np.pricehunt.backend.config.PriceExtractionProperties;
import com.np.pricehunt.backend.config.ScrapeAuditProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.dto.PriceInfo;
import com.np.pricehunt.backend.dto.PriceLlmResult;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import com.np.pricehunt.backend.exception.EmptyExtractionInputException;
import com.np.pricehunt.backend.exception.MalformedLlmOutputException;
import com.np.pricehunt.backend.exception.ScrapeBlockedException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class PriceExtractionOrchestratorTest {

    @Mock
    private LlmPriceExtractionService llmService;

    private PriceExtractionOrchestrator orchestrator;

    private static final PriceLlmResult STUB_LLM_RESULT =
            new PriceLlmResult(new BigDecimal("29.99"), "USD", AvailabilityStatus.AVAILABLE);

    private static final String SNIPPET_MODEL = "openai/gpt-oss-20b";
    private static final String FULLTEXT_MODEL = "openai/gpt-oss-120b";

    @BeforeEach
    void setUp() {
        orchestrator = new PriceExtractionOrchestrator(
                llmService,
                new PriceExtractionProperties(SNIPPET_MODEL, FULLTEXT_MODEL),
                new LlmInputResolver(new ScrapeAuditProperties(Duration.ofDays(90), "0 15 3 * * *", 8000, false)));
    }

    // --- extractPrice waterfall routing ---

    @Test
    void extractPrice_structured_returnsMappedPriceNoLlmCall() {
        ScrapeResponse response = new ScrapeResponse(
                ExtractionSource.STRUCTURED,
                new ScrapeResponse.PriceData(new BigDecimal("49.99"), "EUR", AvailabilityStatus.AVAILABLE),
                null,
                null,
                null);

        PriceInfo result = orchestrator.extractPrice(response);

        assertThat(result.price()).isEqualByComparingTo("49.99");
        assertThat(result.currency()).isEqualTo("EUR");
        assertThat(result.availability()).isEqualTo(AvailabilityStatus.AVAILABLE);
        assertThat(result.extractionSource()).isEqualTo(ExtractionSource.STRUCTURED);
        verifyNoInteractions(llmService);
    }

    @Test
    void extractPrice_structured_nullAvailability_defaultsToUnknown() {
        // A structured payload with no availability must coalesce to UNKNOWN (availabilityOrUnknown),
        // never null — which would violate PriceRecord.availability's NOT NULL contract.
        ScrapeResponse response = new ScrapeResponse(
                ExtractionSource.STRUCTURED,
                new ScrapeResponse.PriceData(new BigDecimal("49.99"), "EUR", null),
                null,
                null,
                null);

        PriceInfo result = orchestrator.extractPrice(response);

        assertThat(result.availability()).isEqualTo(AvailabilityStatus.UNKNOWN);
    }

    @Test
    void extractPrice_structured_nullPriceData_throwsIllegalState() {
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.STRUCTURED, null, null, null, null);

        assertThatThrownBy(() -> orchestrator.extractPrice(response))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("priceData is null");
    }

    @Test
    void extractPrice_snippet_callsLlmWithSnippetAndFastModel() {
        when(llmService.extractPriceFromText("$29.99 | USD | In Stock", SNIPPET_MODEL))
                .thenReturn(STUB_LLM_RESULT);
        ScrapeResponse response =
                new ScrapeResponse(ExtractionSource.SNIPPET, null, "$29.99 | USD | In Stock", null, null);

        PriceInfo result = orchestrator.extractPrice(response);

        assertThat(result.extractionSource()).isEqualTo(ExtractionSource.SNIPPET);
        assertThat(result.price()).isEqualByComparingTo("29.99");
        verify(llmService).extractPriceFromText("$29.99 | USD | In Stock", SNIPPET_MODEL);
        verify(llmService, never()).extractPriceFromText(anyString(), eq(FULLTEXT_MODEL));
    }

    @Test
    void extractPrice_snippet_invalidFastResult_retriesWithAccurateModel() {
        String snippet = "ambiguous text payload";
        PriceLlmResult invalid = new PriceLlmResult(null, null, AvailabilityStatus.UNKNOWN);
        when(llmService.extractPriceFromText(snippet, SNIPPET_MODEL)).thenReturn(invalid);
        when(llmService.extractPriceFromText(snippet, FULLTEXT_MODEL)).thenReturn(STUB_LLM_RESULT);
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.SNIPPET, null, snippet, null, null);

        PriceInfo result = orchestrator.extractPrice(response);

        assertThat(result.extractionSource()).isEqualTo(ExtractionSource.SNIPPET);
        assertThat(result.price()).isEqualByComparingTo("29.99");
        verify(llmService).extractPriceFromText(snippet, SNIPPET_MODEL);
        verify(llmService).extractPriceFromText(snippet, FULLTEXT_MODEL);
    }

    @Test
    void extractPrice_snippet_fastModelMalformedOutput_retriesWithAccurateModel() {
        String snippet = "malformed-json-trigger";
        when(llmService.extractPriceFromText(snippet, SNIPPET_MODEL))
                .thenThrow(
                        new MalformedLlmOutputException(SNIPPET_MODEL, "v1", new RuntimeException("JSON parse error")));
        when(llmService.extractPriceFromText(snippet, FULLTEXT_MODEL)).thenReturn(STUB_LLM_RESULT);
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.SNIPPET, null, snippet, null, null);

        PriceInfo result = orchestrator.extractPrice(response);

        assertThat(result.extractionSource()).isEqualTo(ExtractionSource.SNIPPET);
        assertThat(result.price()).isEqualByComparingTo("29.99");
        verify(llmService).extractPriceFromText(snippet, SNIPPET_MODEL);
        verify(llmService).extractPriceFromText(snippet, FULLTEXT_MODEL);
    }

    // A non-parse exception (a bug, or an Ollama transport/HTTP failure) must NOT escalate to the
    // heavy model — it propagates so the failure surfaces instead of being masked by a slow call.
    @Test
    void extractPrice_snippet_fastModelGenericException_propagatesWithoutEscalating() {
        String snippet = "snippet payload";
        IllegalStateException bug = new IllegalStateException("a bug, not bad output");
        when(llmService.extractPriceFromText(snippet, SNIPPET_MODEL)).thenThrow(bug);
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.SNIPPET, null, snippet, null, null);

        assertThatThrownBy(() -> orchestrator.extractPrice(response)).isSameAs(bug);
        verify(llmService).extractPriceFromText(snippet, SNIPPET_MODEL);
        verify(llmService, never()).extractPriceFromText(anyString(), eq(FULLTEXT_MODEL));
    }

    @ParameterizedTest
    @MethodSource("infraFailures")
    void extractPrice_snippet_fastModelInfraFailure_propagatesWithoutEscalating(RuntimeException infraEx) {
        String snippet = "snippet payload";
        when(llmService.extractPriceFromText(snippet, SNIPPET_MODEL)).thenThrow(infraEx);
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.SNIPPET, null, snippet, null, null);

        assertThatThrownBy(() -> orchestrator.extractPrice(response)).isSameAs(infraEx);
        verify(llmService).extractPriceFromText(snippet, SNIPPET_MODEL);
        verify(llmService, never()).extractPriceFromText(anyString(), eq(FULLTEXT_MODEL));
    }

    static Stream<RuntimeException> infraFailures() {
        return Stream.of(
                new ResourceAccessException("timeout"),
                new TransientAiException("ollama 503"),
                new NonTransientAiException("ollama 400"));
    }

    @Test
    void extractPrice_snippet_heavyModelFailurePropagates() {
        String snippet = "ambiguous payload";
        PriceLlmResult invalid = new PriceLlmResult(null, null, AvailabilityStatus.UNKNOWN);
        when(llmService.extractPriceFromText(snippet, SNIPPET_MODEL)).thenReturn(invalid);
        ResourceAccessException heavyFailure = new ResourceAccessException("heavy model timeout");
        when(llmService.extractPriceFromText(snippet, FULLTEXT_MODEL)).thenThrow(heavyFailure);
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.SNIPPET, null, snippet, null, null);

        assertThatThrownBy(() -> orchestrator.extractPrice(response)).isSameAs(heavyFailure);
        verify(llmService).extractPriceFromText(snippet, FULLTEXT_MODEL);
    }

    // The escalation path's failure-only context (issue #131): when the SNIPPET fast model emits
    // malformed output and the escalated heavy model ALSO does, the propagated exception must attribute
    // to the HEAVY model — so the recorder records the model that actually failed, not the nominal one.
    @Test
    void extractPrice_snippet_bothModelsMalformed_propagatesHeavyModelContext() {
        String snippet = "ambiguous payload trigger";
        when(llmService.extractPriceFromText(snippet, SNIPPET_MODEL))
                .thenThrow(new MalformedLlmOutputException(SNIPPET_MODEL, "v1", new RuntimeException("snip bad")));
        when(llmService.extractPriceFromText(snippet, FULLTEXT_MODEL))
                .thenThrow(new MalformedLlmOutputException(FULLTEXT_MODEL, "v1", new RuntimeException("heavy bad")));
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.SNIPPET, null, snippet, null, null);

        assertThatThrownBy(() -> orchestrator.extractPrice(response))
                .isInstanceOfSatisfying(MalformedLlmOutputException.class, e -> {
                    assertThat(e.getContext()).isNotNull();
                    assertThat(e.getContext().modelName()).isEqualTo(FULLTEXT_MODEL);
                    assertThat(e.getContext().promptVersion()).isEqualTo("v1");
                });
    }

    @Test
    void extractPrice_snippet_bothModelsInvalid_returnsResultWithNulls() {
        String snippet = "ambiguous payload";
        PriceLlmResult invalid = new PriceLlmResult(null, null, AvailabilityStatus.UNKNOWN);
        when(llmService.extractPriceFromText(snippet, SNIPPET_MODEL)).thenReturn(invalid);
        when(llmService.extractPriceFromText(snippet, FULLTEXT_MODEL)).thenReturn(invalid);
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.SNIPPET, null, snippet, null, null);

        PriceInfo result = orchestrator.extractPrice(response);

        assertThat(result.extractionSource()).isEqualTo(ExtractionSource.SNIPPET);
        assertThat(result.price()).isNull();
        assertThat(result.currency()).isNull();
        assertThat(result.availability()).isEqualTo(AvailabilityStatus.UNKNOWN);
        verify(llmService).extractPriceFromText(snippet, SNIPPET_MODEL);
        verify(llmService).extractPriceFromText(snippet, FULLTEXT_MODEL);
    }

    @Test
    void extractPrice_blocked_throwsScrapeBlockedExceptionWithReason() {
        String reason = "cloudflare-managed:cf-ray=9fcfc0abcd123456-TLV";
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.BLOCKED, null, null, null, reason);

        assertThatThrownBy(() -> orchestrator.extractPrice(response))
                .isInstanceOf(ScrapeBlockedException.class)
                .hasMessageContaining(reason);
        verifyNoInteractions(llmService);
    }

    // FULLTEXT with empty innerText is the symptom we hit on Amazon's AWS WAF
    // page before scraper-side detection caught it: tier 3 fell through, body
    // was empty, and the LLM was being called with 0 chars.
    @Test
    void extractPrice_fulltext_emptyInnerText_throwsEmptyExtractionInputException() {
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.FULLTEXT, null, null, "", null);

        assertThatThrownBy(() -> orchestrator.extractPrice(response))
                .isInstanceOf(EmptyExtractionInputException.class)
                .hasMessageContaining("FULLTEXT")
                .hasMessageContaining("chars=0");
        verifyNoInteractions(llmService);
    }

    // Whitespace-only inputs would slip past a raw-length check. guardMinLength
    // measures trimmed length so this still trips the floor and we don't burn an
    // LLM call on payloads that are effectively empty.
    @Test
    void extractPrice_fulltext_whitespaceOnly_throwsEmptyExtractionInputException() {
        ScrapeResponse response =
                new ScrapeResponse(ExtractionSource.FULLTEXT, null, null, "                    ", null);

        assertThatThrownBy(() -> orchestrator.extractPrice(response))
                .isInstanceOf(EmptyExtractionInputException.class)
                .hasMessageContaining("FULLTEXT")
                .hasMessageContaining("chars=0");
        verifyNoInteractions(llmService);
    }

    @Test
    void extractPrice_snippet_belowThreshold_throwsEmptyExtractionInputException() {
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.SNIPPET, null, "abc", null, null);

        assertThatThrownBy(() -> orchestrator.extractPrice(response))
                .isInstanceOf(EmptyExtractionInputException.class)
                .hasMessageContaining("SNIPPET")
                .hasMessageContaining("chars=3");
        verifyNoInteractions(llmService);
    }

    @Test
    void extractPrice_fulltext_callsLlmWithFilteredTextAndAccurateModel() {
        when(llmService.extractPriceFromText(anyString(), eq(FULLTEXT_MODEL))).thenReturn(STUB_LLM_RESULT);
        // lines 0-1 and 5-6 are far enough from any price match that filterLines should drop them
        String body = "dropped first\nalso dropped\n$29.99\nin stock\nalso dropped\ndropped last";
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.FULLTEXT, null, null, body, null);

        PriceInfo result = orchestrator.extractPrice(response);

        assertThat(result.extractionSource()).isEqualTo(ExtractionSource.FULLTEXT);
        // filterLines retains price-relevant lines and their context, drops lines 2+ away from any match
        verify(llmService)
                .extractPriceFromText(
                        argThat(text -> text.contains("$29.99")
                                && !text.contains("dropped first")
                                && !text.contains("dropped last")),
                        eq(FULLTEXT_MODEL));
    }
}
