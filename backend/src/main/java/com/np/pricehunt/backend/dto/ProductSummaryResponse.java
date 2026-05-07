package com.np.pricehunt.backend.dto;

import java.math.BigDecimal;

public record ProductSummaryResponse(
        Long id,
        String name,
        String description,
        int trackedStoreCount,
        BigDecimal bestPrice,
        String bestPriceCurrency,
        String bestPriceShop,
        boolean anyAvailable,
        boolean mixedCurrencies
) {}
