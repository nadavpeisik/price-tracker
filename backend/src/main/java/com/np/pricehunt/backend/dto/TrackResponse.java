package com.np.pricehunt.backend.dto;

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
        LocalDateTime lastChecked
) {}
