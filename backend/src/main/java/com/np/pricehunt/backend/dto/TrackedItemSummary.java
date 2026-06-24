package com.np.pricehunt.backend.dto;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ShopNameSource;
import java.math.BigDecimal;
import java.time.Instant;

public record TrackedItemSummary(
        Long id,
        String url,
        String shopName,
        ShopNameSource shopNameSource,
        BigDecimal currentPrice,
        String currency,
        AvailabilityStatus availability,
        Instant lastChecked) {}
