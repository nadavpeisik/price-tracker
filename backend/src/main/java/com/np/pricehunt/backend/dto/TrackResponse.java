package com.np.pricehunt.backend.dto;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.ShopNameSource;
import java.math.BigDecimal;
import java.time.Instant;

public record TrackResponse(
        Long productId,
        String productName,
        Long trackedItemId,
        String url,
        String shopName,
        ShopNameSource shopNameSource,
        BigDecimal currentPrice,
        String currency,
        AvailabilityStatus availability,
        Instant lastChecked,
        ExtractionSource extractionSource) {}
