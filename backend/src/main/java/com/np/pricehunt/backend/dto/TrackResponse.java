package com.np.pricehunt.backend.dto;

import com.np.pricehunt.backend.domain.ExtractionSource;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TrackResponse(
        Long productId,
        String productName,
        Long trackedItemId,
        String url,
        String shopName,
        BigDecimal currentPrice,
        String currency,
        boolean available,
        LocalDateTime lastChecked,
        ExtractionSource extractionSource
) {}
