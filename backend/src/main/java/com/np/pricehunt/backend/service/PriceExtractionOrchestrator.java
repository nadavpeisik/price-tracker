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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class PriceExtractionOrchestrator implements PriceExtractionService {

    // Floor matches scraper's _snippet_has_useful_content (scraper/main.py). Backstop for the case
    // where the scraper returns a payload but the text is empty/near-empty (undetected bot wall like
    // Amazon's AWS WAF interstitial before detection caught it, or an upstream change). Prevents wasted
    // LLM calls and bogus prices being persisted.
    private static final int MIN_LLM_INPUT_CHARS = 15;

    private final LlmPriceExtractionService llmService;
    private final PriceExtractionProperties extractionProperties;
    // Single source of truth for the LLM input (issue #131): the recorder re-derives the same text, so
    // a persisted scrape_attempt's llm_input is byte-identical to what the model saw here.
    private final LlmInputResolver llmInputResolver;

    @Override
    public PriceInfo extractPrice(ScrapeResponse response) {
        return switch (response.extractionSource()) {
            case BLOCKED -> throw new ScrapeBlockedException(response.blockedReason());
            case STRUCTURED -> mapStructured(response.priceData());
            case SNIPPET -> {
                String text = llmInputResolver.resolve(response);
                guardMinLength(text, "SNIPPET");
                PriceLlmResult raw;
                try {
                    raw = llmService.extractPriceFromText(text, extractionProperties.snippetModel());
                } catch (MalformedLlmOutputException e) {
                    // Fast model emitted unparseable output — let the bigger model try. Transport
                    // failures, provider 4xx/5xx, and bugs are NOT caught here; they propagate.
                    log.info("SNIPPET fast model returned malformed output — retrying with accurate model");
                    raw = null;
                }

                if (!isValidLlmResult(raw)) {
                    if (raw != null) {
                        log.info("SNIPPET fast model returned invalid result {}, retrying with accurate model", raw);
                    }
                    raw = llmService.extractPriceFromText(text, extractionProperties.fulltextModel());
                }
                yield new PriceInfo(
                        raw.price(),
                        raw.currency(),
                        availabilityOrUnknown(raw.availability()),
                        ExtractionSource.SNIPPET);
            }
            case FULLTEXT -> {
                String text = llmInputResolver.resolve(response);
                guardMinLength(text, "FULLTEXT");
                PriceLlmResult raw = llmService.extractPriceFromText(text, extractionProperties.fulltextModel());
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
}
