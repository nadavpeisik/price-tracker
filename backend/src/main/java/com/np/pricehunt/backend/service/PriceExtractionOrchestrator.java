package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.dto.PriceInfo;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Service
@Primary
@RequiredArgsConstructor
public class PriceExtractionOrchestrator implements PriceExtractionService {

    private static final int MAX_FILTER_LINES = 50;
    private static final int MAX_FILTER_CHARS = 2000;
    private static final int FALLBACK_CHARS = 3000;

    // Matches currency symbols/codes and price-related keywords (US and EU number formats)
    private static final Pattern PRICE_LINE_PATTERN = Pattern.compile(
            "[$£€¥₩]\\s*[\\d.,]+" +
            "|[\\d.,]+\\s*[$£€¥₩]" +
            "|\\b(USD|GBP|EUR|JPY|CAD|AUD|CHF|CNY|ILS|price|cost|sale|discount|" +
            "in stock|out of stock|add to cart|buy now|availability)\\b",
            Pattern.CASE_INSENSITIVE
    );

    private final OllamaPriceExtractionService ollamaService;

    @Override
    public PriceInfo extractPrice(ScrapeResponse response) {
        return switch (response.extractionSource()) {
            case STRUCTURED -> mapStructured(response.priceData());
            case SNIPPET -> {
                OllamaPriceExtractionService.PriceLlmResult raw = ollamaService.extractPriceFromText(response.snippet());
                yield new PriceInfo(raw.price(), raw.currency(), raw.available(), ExtractionSource.SNIPPET);
            }
            case FULLTEXT -> {
                OllamaPriceExtractionService.PriceLlmResult raw = ollamaService.extractPriceFromText(filterLines(response.innerText()));
                yield new PriceInfo(raw.price(), raw.currency(), raw.available(), ExtractionSource.FULLTEXT);
            }
        };
    }

    private PriceInfo mapStructured(ScrapeResponse.PriceData d) {
        return new PriceInfo(d.price(), d.currency(), d.available(), ExtractionSource.STRUCTURED);
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
