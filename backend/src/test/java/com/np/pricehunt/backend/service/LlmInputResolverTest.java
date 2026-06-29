package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.np.pricehunt.backend.config.ScrapeAuditProperties;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class LlmInputResolverTest {

    private static ScrapeAuditProperties props(int maxLlmInputChars) {
        return new ScrapeAuditProperties(Duration.ofDays(90), "0 15 3 * * *", maxLlmInputChars, false);
    }

    private final LlmInputResolver inputs = new LlmInputResolver(props(8000));

    private static ScrapeResponse response(ExtractionSource source, String snippet, String innerText) {
        return new ScrapeResponse(source, null, snippet, innerText, null);
    }

    // --- resolve per source ---

    @Test
    void resolve_snippet_returnsSnippetVerbatim() {
        assertThat(inputs.resolve(response(ExtractionSource.SNIPPET, "$29.99 | USD | In Stock", null)))
                .isEqualTo("$29.99 | USD | In Stock");
    }

    @Test
    void resolve_fulltext_returnsFilteredText() {
        String result = inputs.resolve(response(ExtractionSource.FULLTEXT, null, "Product name\n$29.99\nIn stock"));
        assertThat(result).isEqualTo("Product name\n$29.99\nIn stock");
    }

    @Test
    void resolve_structured_returnsNull() {
        assertThat(inputs.resolve(response(ExtractionSource.STRUCTURED, null, null)))
                .isNull();
    }

    @Test
    void resolve_blocked_returnsNull() {
        assertThat(inputs.resolve(new ScrapeResponse(ExtractionSource.BLOCKED, null, null, null, "cloudflare")))
                .isNull();
    }

    // --- the maxLlmInputChars ceiling: a pathological single long line stays bounded ---

    @Test
    void resolve_pathologicalLongLine_isCappedToCeiling() {
        LlmInputResolver capped = new LlmInputResolver(props(3000));
        String giant = "$50 " + "x".repeat(5000); // one matched line well over the ceiling
        String result = capped.resolve(response(ExtractionSource.FULLTEXT, null, giant));
        assertThat(result).hasSize(3000);
    }

    @Test
    void resolve_normalFulltext_isNotTruncated() {
        // A normal filtered FULLTEXT (well under the ceiling) is returned intact.
        String result = inputs.resolve(response(ExtractionSource.FULLTEXT, null, "specs\n$29.99\nIn stock\nreviews"));
        assertThat(result).contains("$29.99").doesNotContain("x".repeat(10));
    }

    @Test
    void constructor_rejectsCeilingBelowFulltextBudget() {
        // Below filterLines' 3000-char fallback budget the cap would sever legitimate FULLTEXT — fail boot.
        assertThatThrownBy(() -> new LlmInputResolver(props(100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("max-llm-input-chars");
    }

    // --- filterLines (moved here from the orchestrator as the single source of truth) ---

    @Test
    void filterLines_nullOrBlank_returnsEmpty() {
        assertThat(LlmInputResolver.filterLines(null)).isEmpty();
        assertThat(LlmInputResolver.filterLines("  \n  ")).isEmpty();
    }

    @Test
    void filterLines_noMatches_longInput_truncatesToFallback() {
        assertThat(LlmInputResolver.filterLines("y".repeat(4000))).hasSize(3000);
    }

    @Test
    void filterLines_keepsPriceLineWithContext() {
        assertThat(LlmInputResolver.filterLines("Product name\n$29.99\nIn stock"))
                .isEqualTo("Product name\n$29.99\nIn stock");
    }

    @Test
    void filterLines_noMatches_shortInput_returnsVerbatim() {
        String noMatch = "hello world\nnothing here\njust text";
        assertThat(LlmInputResolver.filterLines(noMatch)).isEqualTo(noMatch);
    }

    @Test
    void filterLines_consecutiveMatches_noDuplicateLines() {
        String[] lines =
                LlmInputResolver.filterLines("header\n$29.99\n€25.00\nfooter").split("\n");
        assertThat(lines).containsExactly("header", "$29.99", "€25.00", "footer");
    }

    @Test
    void filterLines_matchAtFirstLine_noIndexOutOfBounds() {
        assertThat(LlmInputResolver.filterLines("$29.99\nnext line")).isEqualTo("$29.99\nnext line");
    }

    @Test
    void filterLines_matchAtLastLine_noIndexOutOfBounds() {
        assertThat(LlmInputResolver.filterLines("prev line\n$29.99")).isEqualTo("prev line\n$29.99");
    }

    @Test
    void filterLines_keepsAvailabilityLine_farFromPrice() {
        // The availability phrase is several lines from the price, so it survives ONLY by matching
        // PRICE_LINE_PATTERN itself (not by price-adjacency). The FULLTEXT prompt classifies on these
        // signals, so the filter must keep them or the model never sees the decisive evidence.
        String input = "Product name\n$29.99\nspecs\nreviews\nNo longer available\nfooter";
        assertThat(LlmInputResolver.filterLines(input)).contains("No longer available");
    }

    @Test
    void filterLines_keepsOutOfStockLine_farFromPrice() {
        String input = "Product name\n$29.99\nspecs\nreviews\nOut of stock\nfooter";
        assertThat(LlmInputResolver.filterLines(input)).contains("Out of stock");
    }

    @Test
    void filterLines_keepsLowStockUrgencyLine_farFromPrice() {
        // AVAILABLE-side signals the prompt classifies on must survive too, not just OOS phrases.
        String input = "Product name\n$29.99\nspecs\nreviews\nOnly 2 left in stock\nfooter";
        assertThat(LlmInputResolver.filterLines(input)).contains("Only 2 left in stock");
    }

    @Test
    void filterLines_keepsHebrewOutOfStockLine_farFromPrice() {
        // Hebrew availability signals must survive too — exercises the UNICODE_CASE matching path.
        String input = "מוצר\n$29.99\nמפרט\nביקורות\nחסר במלאי\nכותרת";
        assertThat(LlmInputResolver.filterLines(input)).contains("חסר במלאי");
    }
}
