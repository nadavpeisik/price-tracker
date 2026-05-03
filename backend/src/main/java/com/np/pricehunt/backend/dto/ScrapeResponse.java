package com.np.pricehunt.backend.dto;

import com.np.pricehunt.backend.domain.ExtractionSource;

import java.math.BigDecimal;

public record ScrapeResponse(
        ExtractionSource extractionSource,
        PriceData priceData,
        String snippet,
        String innerText
) {
    public record PriceData(BigDecimal price, String currency, boolean available) {}
}
