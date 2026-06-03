package com.np.pricehunt.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ProductSummaryResponse(
        Long id,
        String name,
        String description,
        int trackedStoreCount,
        BigDecimal bestPriceConverted,
        String bestPriceConvertedCurrency,
        BigDecimal bestPriceOriginal,
        String bestPriceOriginalCurrency,
        String bestPriceShop,
        LocalDate conversionAsOf,
        boolean conversionStale,
        PriceBasis priceBasis,
        boolean anyAvailable,
        boolean mixedCurrencies) {}
