package com.np.pricehunt.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record TrackedItemSummary(
        Long id,
        String url,
        String shopName,
        BigDecimal currentPrice,
        String currency,
        boolean available,
        LocalDateTime lastChecked
) {}
