package com.np.pricehunt.backend.dto;

import com.np.pricehunt.backend.domain.ExtractionSource;

import java.math.BigDecimal;
import java.time.Instant;

public record TrackResponse(
        Long productId,
        String productName,
        Long trackedItemId,
        String url,
        String shopName,
        BigDecimal currentPrice,
        String currency,
        boolean available,
        Instant lastChecked,
        ExtractionSource extractionSource
) {}
