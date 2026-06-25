package com.np.pricehunt.backend.dto;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import java.math.BigDecimal;

public record ScrapeResponse(
        ExtractionSource extractionSource,
        PriceData priceData,
        String snippet,
        String innerText,
        String blockedReason,
        ShopNameProposal shopNameProposal) {

    public record PriceData(BigDecimal price, String currency, AvailabilityStatus availability) {}

    /**
     * The scraper's proposed shop name plus how confident the signal is: {@code strong} = a
     * site-level signal (og:site_name / JSON-LD Organization), weak = a {@code <title>} guess. This
     * is a <em>proposal</em>, not the final name — the backend resolver decides the stored name (a
     * curated/learned mapping can override even a strong proposal). Null when nothing was detected.
     */
    public record ShopNameProposal(String name, boolean strong) {}

    // Convenience for call sites that don't exercise shop-name detection (chiefly price-focused
    // tests); shopNameProposal defaults to null. Production deserialization uses the canonical
    // 6-arg constructor via Jackson (matched by record-component name).
    public ScrapeResponse(
            ExtractionSource extractionSource,
            PriceData priceData,
            String snippet,
            String innerText,
            String blockedReason) {
        this(extractionSource, priceData, snippet, innerText, blockedReason, null);
    }
}
