package com.np.pricehunt.backend.dto;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
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
        AvailabilityStatus availability,
        boolean mixedCurrencies) {}
