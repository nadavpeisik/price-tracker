package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.np.pricehunt.backend.config.PriceExtractionProperties;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.dto.PriceInfo;
import com.np.pricehunt.backend.dto.PriceLlmResult;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import com.np.pricehunt.backend.exception.EmptyExtractionInputException;
import com.np.pricehunt.backend.exception.MalformedLlmOutputException;
import com.np.pricehunt.backend.exception.ScrapeBlockedException;
import java.math.BigDecimal;
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
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;

@ExtendWith(MockitoExtension.class)
class PriceExtractionOrchestratorTest {

    @Mock
    private OllamaPriceExtractionService ollamaService;

    private PriceExtractionOrchestrator orchestrator;

    private static final PriceLlmResult STUB_LLM_RESULT = new PriceLlmResult(new BigDecimal("29.99"), "USD", true);

    private static final String SNIPPET_MODEL = "qwen3:1.7b";
    private static final String FULLTEXT_MODEL = "qwen3.5:9b";

    @BeforeEach
    void setUp() {
        orchestrator = new PriceExtractionOrchestrator(
                ollamaService, new PriceExtractionProperties(SNIPPET_MODEL, FULLTEXT_MODEL));
    }

    // --- filterLines ---

    @Test
    void filterLines_nullInput_returnsEmpty() {
        assertThat(orchestrator.filterLines(null)).isEmpty();
    }

    @Test
    void filterLines_blankInput_returnsEmpty() {
        assertThat(orchestrator.filterLines("   \n  \n  ")).isEmpty();
    }

    @Test
    void filterLines_noMatches_returnsTruncatedFallback() {
        String noMatch = "hello world\nnothing here\njust text";
        assertThat(orchestrator.filterLines(noMatch)).isEqualTo(noMatch);
    }

    @Test
    void filterLines_noMatches_longInput_truncatesToFallbackChars() {
        String longText = "x".repeat(4000);
        String result = orchestrator.filterLines(longText);
        assertThat(result).hasSize(3000);
    }

    @Test
    void filterLines_singleMatch_includesPreviousAndNextLine() {
        String input = "Product name\n$29.99\nIn stock";
        String result = orchestrator.filterLines(input);
        assertThat(result).isEqualTo("Product name\n$29.99\nIn stock");
    }

    @Test
    void filterLines_consecutiveMatches_noDuplicateLines() {
        String input = "header\n$29.99\n€25.00\nfooter";
        String result = orchestrator.filterLines(input);
        String[] lines = result.split("\n");
        // all 4 lines present exactly once
        assertThat(lines).containsExactly("header", "$29.99", "€25.00", "footer");
    }

    @Test
    void filterLines_matchAtFirstLine_noIndexOutOfBounds() {
        String input = "$29.99\nnext line";
        assertThat(orchestrator.filterLines(input)).isEqualTo("$29.99\nnext line");
    }

    @Test
    void filterLines_matchAtLastLine_noIndexOutOfBounds() {
        String input = "prev line\n$29.99";
        assertThat(orchestrator.filterLines(input)).isEqualTo("prev line\n$29.99");
    }

    // --- extractPrice waterfall routing ---

    @Test
    void extractPrice_structured_returnsMappedPriceNoLlmCall() {
        ScrapeResponse response = new ScrapeResponse(
                ExtractionSource.STRUCTURED,
                new ScrapeResponse.PriceData(new BigDecimal("49.99"), "EUR", true),
                null,
                null,
                null);

        PriceInfo result = orchestrator.extractPrice(response);

        assertThat(result.price()).isEqualByComparingTo("49.99");
        assertThat(result.currency()).isEqualTo("EUR");
        assertThat(result.available()).isTrue();
        assertThat(result.extractionSource()).isEqualTo(ExtractionSource.STRUCTURED);
        verifyNoInteractions(ollamaService);
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
        when(ollamaService.extractPriceFromText("$29.99 | USD | In Stock", SNIPPET_MODEL))
                .thenReturn(STUB_LLM_RESULT);
        ScrapeResponse response =
                new ScrapeResponse(ExtractionSource.SNIPPET, null, "$29.99 | USD | In Stock", null, null);

        PriceInfo result = orchestrator.extractPrice(response);

        assertThat(result.extractionSource()).isEqualTo(ExtractionSource.SNIPPET);
        assertThat(result.price()).isEqualByComparingTo("29.99");
        verify(ollamaService).extractPriceFromText("$29.99 | USD | In Stock", SNIPPET_MODEL);
        verify(ollamaService, never()).extractPriceFromText(anyString(), eq(FULLTEXT_MODEL));
    }

    @Test
    void extractPrice_snippet_invalidFastResult_retriesWithAccurateModel() {
        String snippet = "ambiguous text payload";
        PriceLlmResult invalid = new PriceLlmResult(null, null, false);
        when(ollamaService.extractPriceFromText(snippet, SNIPPET_MODEL)).thenReturn(invalid);
        when(ollamaService.extractPriceFromText(snippet, FULLTEXT_MODEL)).thenReturn(STUB_LLM_RESULT);
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.SNIPPET, null, snippet, null, null);

        PriceInfo result = orchestrator.extractPrice(response);

        assertThat(result.extractionSource()).isEqualTo(ExtractionSource.SNIPPET);
        assertThat(result.price()).isEqualByComparingTo("29.99");
        verify(ollamaService).extractPriceFromText(snippet, SNIPPET_MODEL);
        verify(ollamaService).extractPriceFromText(snippet, FULLTEXT_MODEL);
    }

    @Test
    void extractPrice_snippet_fastModelMalformedOutput_retriesWithAccurateModel() {
        String snippet = "malformed-json-trigger";
        when(ollamaService.extractPriceFromText(snippet, SNIPPET_MODEL))
                .thenThrow(new MalformedLlmOutputException(SNIPPET_MODEL, new RuntimeException("JSON parse error")));
        when(ollamaService.extractPriceFromText(snippet, FULLTEXT_MODEL)).thenReturn(STUB_LLM_RESULT);
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.SNIPPET, null, snippet, null, null);

        PriceInfo result = orchestrator.extractPrice(response);

        assertThat(result.extractionSource()).isEqualTo(ExtractionSource.SNIPPET);
        assertThat(result.price()).isEqualByComparingTo("29.99");
        verify(ollamaService).extractPriceFromText(snippet, SNIPPET_MODEL);
        verify(ollamaService).extractPriceFromText(snippet, FULLTEXT_MODEL);
    }

    // A non-parse exception (a bug, or an Ollama transport/HTTP failure) must NOT escalate to the
    // heavy model — it propagates so the failure surfaces instead of being masked by a slow call.
    @Test
    void extractPrice_snippet_fastModelGenericException_propagatesWithoutEscalating() {
        String snippet = "snippet payload";
        IllegalStateException bug = new IllegalStateException("a bug, not bad output");
        when(ollamaService.extractPriceFromText(snippet, SNIPPET_MODEL)).thenThrow(bug);
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.SNIPPET, null, snippet, null, null);

        assertThatThrownBy(() -> orchestrator.extractPrice(response)).isSameAs(bug);
        verify(ollamaService).extractPriceFromText(snippet, SNIPPET_MODEL);
        verify(ollamaService, never()).extractPriceFromText(anyString(), eq(FULLTEXT_MODEL));
    }

    @ParameterizedTest
    @MethodSource("infraFailures")
    void extractPrice_snippet_fastModelInfraFailure_propagatesWithoutEscalating(RuntimeException infraEx) {
        String snippet = "snippet payload";
        when(ollamaService.extractPriceFromText(snippet, SNIPPET_MODEL)).thenThrow(infraEx);
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.SNIPPET, null, snippet, null, null);

        assertThatThrownBy(() -> orchestrator.extractPrice(response)).isSameAs(infraEx);
        verify(ollamaService).extractPriceFromText(snippet, SNIPPET_MODEL);
        verify(ollamaService, never()).extractPriceFromText(anyString(), eq(FULLTEXT_MODEL));
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
        PriceLlmResult invalid = new PriceLlmResult(null, null, false);
        when(ollamaService.extractPriceFromText(snippet, SNIPPET_MODEL)).thenReturn(invalid);
        ResourceAccessException heavyFailure = new ResourceAccessException("heavy model timeout");
        when(ollamaService.extractPriceFromText(snippet, FULLTEXT_MODEL)).thenThrow(heavyFailure);
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.SNIPPET, null, snippet, null, null);

        assertThatThrownBy(() -> orchestrator.extractPrice(response)).isSameAs(heavyFailure);
        verify(ollamaService).extractPriceFromText(snippet, FULLTEXT_MODEL);
    }

    @Test
    void extractPrice_snippet_bothModelsInvalid_returnsResultWithNulls() {
        String snippet = "ambiguous payload";
        PriceLlmResult invalid = new PriceLlmResult(null, null, false);
        when(ollamaService.extractPriceFromText(snippet, SNIPPET_MODEL)).thenReturn(invalid);
        when(ollamaService.extractPriceFromText(snippet, FULLTEXT_MODEL)).thenReturn(invalid);
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.SNIPPET, null, snippet, null, null);

        PriceInfo result = orchestrator.extractPrice(response);

        assertThat(result.extractionSource()).isEqualTo(ExtractionSource.SNIPPET);
        assertThat(result.price()).isNull();
        assertThat(result.currency()).isNull();
        assertThat(result.available()).isFalse();
        verify(ollamaService).extractPriceFromText(snippet, SNIPPET_MODEL);
        verify(ollamaService).extractPriceFromText(snippet, FULLTEXT_MODEL);
    }

    @Test
    void extractPrice_blocked_throwsScrapeBlockedExceptionWith502AndReason() {
        String reason = "cloudflare-managed:cf-ray=9fcfc0abcd123456-TLV";
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.BLOCKED, null, null, null, reason);

        assertThatThrownBy(() -> orchestrator.extractPrice(response))
                .isInstanceOf(ScrapeBlockedException.class)
                .satisfies(e ->
                        assertThat(((ScrapeBlockedException) e).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessageContaining(reason);
        verifyNoInteractions(ollamaService);
    }

    // FULLTEXT with empty innerText is the symptom we hit on Amazon's AWS WAF
    // page before scraper-side detection caught it: tier 3 fell through, body
    // was empty, and the LLM was being called with 0 chars.
    @Test
    void extractPrice_fulltext_emptyInnerText_throwsEmptyExtractionInputException() {
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.FULLTEXT, null, null, "", null);

        assertThatThrownBy(() -> orchestrator.extractPrice(response))
                .isInstanceOf(EmptyExtractionInputException.class)
                .satisfies(e -> assertThat(((EmptyExtractionInputException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessageContaining("FULLTEXT")
                .hasMessageContaining("chars=0");
        verifyNoInteractions(ollamaService);
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
        verifyNoInteractions(ollamaService);
    }

    @Test
    void extractPrice_snippet_belowThreshold_throwsEmptyExtractionInputException() {
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.SNIPPET, null, "abc", null, null);

        assertThatThrownBy(() -> orchestrator.extractPrice(response))
                .isInstanceOf(EmptyExtractionInputException.class)
                .satisfies(e -> assertThat(((EmptyExtractionInputException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_GATEWAY))
                .hasMessageContaining("SNIPPET")
                .hasMessageContaining("chars=3");
        verifyNoInteractions(ollamaService);
    }

    @Test
    void extractPrice_fulltext_callsLlmWithFilteredTextAndAccurateModel() {
        when(ollamaService.extractPriceFromText(anyString(), eq(FULLTEXT_MODEL)))
                .thenReturn(STUB_LLM_RESULT);
        // lines 0-1 and 5-6 are far enough from any price match that filterLines should drop them
        String body = "dropped first\nalso dropped\n$29.99\nin stock\nalso dropped\ndropped last";
        ScrapeResponse response = new ScrapeResponse(ExtractionSource.FULLTEXT, null, null, body, null);

        PriceInfo result = orchestrator.extractPrice(response);

        assertThat(result.extractionSource()).isEqualTo(ExtractionSource.FULLTEXT);
        // filterLines retains price-relevant lines and their context, drops lines 2+ away from any match
        verify(ollamaService)
                .extractPriceFromText(
                        argThat(text -> text.contains("$29.99")
                                && !text.contains("dropped first")
                                && !text.contains("dropped last")),
                        eq(FULLTEXT_MODEL));
    }
}
