package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.config.ScrapeAuditProperties;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * The single definition of "what exact text the LLM is fed" for a scrape (issue #131). {@link
 * #resolve(ScrapeResponse)} maps a {@code ScrapeResponse} to the model input — SNIPPET → the raw
 * snippet, FULLTEXT → {@link #filterLines}, STRUCTURED/BLOCKED → null — under one {@code
 * maxLlmInputChars} safety ceiling (a pathological-bloat net set well above {@code filterLines}' own
 * ~2000/3000 budget, so it never severs legitimate input).
 *
 * <p>It's a shared bean precisely so the two callers can't drift: both {@link
 * PriceExtractionOrchestrator} (which feeds the model) and {@code ScrapeAttemptRecorder} (which
 * persists + hashes the input) derive it here, so the recorded {@code llm_input} stays byte-identical
 * to what the model saw.
 */
@Slf4j
@Component
public class LlmInputResolver {

    private static final int MAX_FILTER_LINES = 50;
    private static final int MAX_FILTER_CHARS = 2000;
    private static final int FALLBACK_CHARS = 3000;
    // Defensive guard on the raw input to filterLines' split() — well above any pruned product page, so
    // legitimate content is untouched; only a pathologically huge page is clamped before the array alloc.
    private static final int MAX_INNERTEXT_CHARS = 500_000;

    // Matches currency symbols/codes, price keywords, and availability signals (US and EU number
    // formats). The availability vocabulary must cover every signal the LLM prompt classifies on —
    // otherwise filterLines() strips the evidence (e.g. "sold out", "pre-order") before the model
    // sees it, and the model defaults to UNKNOWN. Hebrew OOS phrases are appended without \b (Hebrew
    // letters aren't ASCII word chars, so \b wouldn't anchor them).
    private static final Pattern PRICE_LINE_PATTERN = Pattern.compile(
            "[$£€¥₩₪]\\s*[\\d.,]+" + "|[\\d.,]+\\s*[$£€¥₩₪]"
                    + "|\\b(USD|GBP|EUR|JPY|CAD|AUD|CHF|CNY|ILS|price|cost|sale|discount|"
                    + "stock|sold out|unavailable|availab\\w*|discontinued|selling fast|order soon|"
                    + "while supplies last|pre-?orders?|back-?order(ed)?|notify me|email me|coming soon|"
                    + "add to cart|buy now)\\b"
                    + "|חסר במלאי|אזל מהמלאי|לא במלאי|הזמנה מראש",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private final int maxLlmInputChars;

    public LlmInputResolver(ScrapeAuditProperties properties) {
        this.maxLlmInputChars = properties.maxLlmInputChars();
        if (maxLlmInputChars < FALLBACK_CHARS) {
            // A cap below filterLines' fallback budget would truncate legitimate FULLTEXT — fail the
            // boot rather than silently sever price/availability evidence on a busy page.
            throw new IllegalArgumentException(
                    "scrape.audit.max-llm-input-chars (%d) must be >= %d (the FULLTEXT budget)"
                            .formatted(maxLlmInputChars, FALLBACK_CHARS));
        }
    }

    /**
     * The exact text fed to the LLM for {@code response}: the raw snippet (SNIPPET), the regex-filtered
     * innerText (FULLTEXT), or {@code null} (STRUCTURED/BLOCKED — no LLM call). Bounded by the
     * configured ceiling. Must stay byte-identical to what {@code extractPrice} feeds the model.
     */
    public String resolve(ScrapeResponse response) {
        String text =
                switch (response.extractionSource()) {
                    case SNIPPET -> response.snippet();
                    case FULLTEXT -> filterLines(response.innerText());
                    case STRUCTURED, BLOCKED -> null;
                };
        return cap(text);
    }

    private String cap(String text) {
        if (text == null || text.length() <= maxLlmInputChars) {
            return text;
        }
        log.warn(
                "LLM input exceeded the {}-char ceiling (was {} chars) — truncating; pathological page bloat",
                maxLlmInputChars,
                text.length());
        return text.substring(0, maxLlmInputChars);
    }

    /**
     * Reduces pruned innerText to the price/availability-relevant lines (plus one line of context each
     * side), capped at ~{@value #MAX_FILTER_CHARS} chars; falls back to the first
     * {@value #FALLBACK_CHARS} chars when nothing matches. Pure and deterministic. Package-private (not
     * private) so the same-package {@code LlmInputResolverTest} can unit-test this intricate FULLTEXT
     * line-reduction directly, with focused inputs instead of constructed {@code ScrapeResponse}s.
     */
    static String filterLines(String innerText) {
        if (innerText == null || innerText.isBlank()) return "";
        // Clamp before split() so a pathologically large page can't OOM the line array (the output is
        // capped later by resolve anyway). Generous bound → no effect on real pruned pages.
        if (innerText.length() > MAX_INNERTEXT_CHARS) {
            innerText = innerText.substring(0, MAX_INNERTEXT_CHARS);
        }

        String[] lines = innerText.split("\n");
        List<String> matched = new ArrayList<>();
        int charCount = 0;
        int lastAddedIndex = -1;

        for (int i = 0; i < lines.length && matched.size() < MAX_FILTER_LINES && charCount < MAX_FILTER_CHARS; i++) {
            if (!PRICE_LINE_PATTERN.matcher(lines[i]).find()) {
                continue;
            }
            // Keep this line plus one neighbor each side, skipping any already taken (windows can overlap).
            int from = Math.max(Math.max(0, i - 1), lastAddedIndex + 1);
            int end = Math.min(lines.length - 1, i + 1);
            for (int j = from; j <= end; j++) {
                matched.add(lines[j]);
                charCount += lines[j].length();
            }
            lastAddedIndex = Math.max(lastAddedIndex, end);
        }

        if (matched.isEmpty()) {
            return innerText.length() > FALLBACK_CHARS ? innerText.substring(0, FALLBACK_CHARS) : innerText;
        }

        return String.join("\n", matched);
    }
}
