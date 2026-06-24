package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.config.PriceExtractionProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.dto.PriceInfo;
import com.np.pricehunt.backend.dto.PriceLlmResult;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import com.np.pricehunt.backend.exception.EmptyExtractionInputException;
import com.np.pricehunt.backend.exception.MalformedLlmOutputException;
import com.np.pricehunt.backend.exception.ScrapeBlockedException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceExtractionOrchestrator implements PriceExtractionService {

    private static final int MAX_FILTER_LINES = 50;
    private static final int MAX_FILTER_CHARS = 2000;
    private static final int FALLBACK_CHARS = 3000;
    // Floor matches scraper's _snippet_has_useful_content (scraper/main.py:437).
    // Backstop for the case where the scraper returns a payload but the text is
    // empty/near-empty (undetected bot wall like Amazon's AWS WAF interstitial
    // before detection caught it, or an upstream change). Prevents wasted LLM
    // calls and bogus prices being persisted.
    private static final int MIN_LLM_INPUT_CHARS = 15;

    // Matches currency symbols/codes, price keywords, and availability signals (US and EU number
    // formats). The availability vocabulary must cover every signal the LLM prompt classifies on —
    // otherwise filterLines() strips the evidence (e.g. "sold out", "pre-order") before the model
    // sees it, and the model defaults to UNKNOWN. Hebrew OOS phrases are appended without \b (Hebrew
    // letters aren't ASCII word chars, so \b wouldn't anchor them).
    private static final Pattern PRICE_LINE_PATTERN = Pattern.compile(
            "[$£€¥₩]\\s*[\\d.,]+" + "|[\\d.,]+\\s*[$£€¥₩]"
                    + "|\\b(USD|GBP|EUR|JPY|CAD|AUD|CHF|CNY|ILS|price|cost|sale|discount|"
                    + "stock|sold out|unavailable|availab\\w*|discontinued|selling fast|order soon|"
                    + "while supplies last|pre-?orders?|back-?order(ed)?|notify me|email me|coming soon|"
                    + "add to cart|buy now)\\b"
                    + "|חסר במלאי|אזל מהמלאי|לא במלאי|הזמנה מראש",
            Pattern.CASE_INSENSITIVE);

    private final OllamaPriceExtractionService ollamaService;
    private final PriceExtractionProperties extractionProperties;

    @Override
    public PriceInfo extractPrice(ScrapeResponse response) {
        return switch (response.extractionSource()) {
            case BLOCKED -> throw new ScrapeBlockedException(response.blockedReason());
            case STRUCTURED -> mapStructured(response.priceData());
            case SNIPPET -> {
                String text = response.snippet() == null ? "" : response.snippet();
                guardMinLength(text, "SNIPPET");
                PriceLlmResult raw;
                try {
                    raw = ollamaService.extractPriceFromText(text, extractionProperties.snippetModel());
                } catch (MalformedLlmOutputException e) {
                    // Fast model emitted unparseable output — let the bigger model try. Transport
                    // failures, Ollama 4xx/5xx, and bugs are NOT caught here; they propagate.
                    log.info("SNIPPET fast model returned malformed output — retrying with accurate model");
                    raw = null;
                }

                if (!isValidLlmResult(raw)) {
                    if (raw != null) {
                        log.info("SNIPPET fast model returned invalid result {}, retrying with accurate model", raw);
                    }
                    raw = ollamaService.extractPriceFromText(text, extractionProperties.fulltextModel());
                }
                yield new PriceInfo(
                        raw.price(),
                        raw.currency(),
                        availabilityOrUnknown(raw.availability()),
                        ExtractionSource.SNIPPET);
            }
            case FULLTEXT -> {
                String text = filterLines(response.innerText());
                guardMinLength(text, "FULLTEXT");
                PriceLlmResult raw = ollamaService.extractPriceFromText(text, extractionProperties.fulltextModel());
                yield new PriceInfo(
                        raw.price(),
                        raw.currency(),
                        availabilityOrUnknown(raw.availability()),
                        ExtractionSource.FULLTEXT);
            }
        };
    }

    private void guardMinLength(String text, String source) {
        int len = text == null ? 0 : text.trim().length();
        if (len < MIN_LLM_INPUT_CHARS) {
            throw new EmptyExtractionInputException(source, len);
        }
    }

    // Shape check, not a semantic check. We deliberately do not validate `availability`:
    // UNKNOWN is a legitimate value, and availability accuracy is the prompt's job — a
    // confidently-wrong AVAILABLE/UNAVAILABLE here can't be detected by a predicate.
    private boolean isValidLlmResult(PriceLlmResult r) {
        return r != null
                && r.price() != null
                && r.price().compareTo(BigDecimal.ZERO) > 0
                && r.currency() != null
                && !r.currency().isBlank();
    }

    private PriceInfo mapStructured(ScrapeResponse.PriceData d) {
        if (d == null) throw new IllegalStateException("extractionSource=STRUCTURED but priceData is null");
        return new PriceInfo(
                d.price(), d.currency(), availabilityOrUnknown(d.availability()), ExtractionSource.STRUCTURED);
    }

    // Defensive coalesce: a null availability (LLM omitted the field, or a structured payload without
    // one) becomes UNKNOWN — never null, which would violate PriceRecord.availability's NOT NULL.
    private static AvailabilityStatus availabilityOrUnknown(AvailabilityStatus availability) {
        return availability != null ? availability : AvailabilityStatus.UNKNOWN;
    }

    String filterLines(String innerText) {
        if (innerText == null || innerText.isBlank()) return "";

        String[] lines = innerText.split("\n");
        List<String> matched = new ArrayList<>();
        int charCount = 0;
        int lastAddedIndex = -1;

        for (int i = 0; i < lines.length && matched.size() < MAX_FILTER_LINES && charCount < MAX_FILTER_CHARS; i++) {
            if (PRICE_LINE_PATTERN.matcher(lines[i]).find()) {
                int start = Math.max(0, i - 1);
                int end = Math.min(lines.length - 1, i + 1);
                for (int j = start; j <= end; j++) {
                    if (j > lastAddedIndex) {
                        matched.add(lines[j]);
                        charCount += lines[j].length();
                        lastAddedIndex = j;
                    }
                }
            }
        }

        if (matched.isEmpty()) {
            return innerText.length() > FALLBACK_CHARS ? innerText.substring(0, FALLBACK_CHARS) : innerText;
        }

        return String.join("\n", matched);
    }
}
