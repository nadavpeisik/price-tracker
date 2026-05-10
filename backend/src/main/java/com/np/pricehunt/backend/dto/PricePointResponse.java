package com.np.pricehunt.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PricePointResponse(
        BigDecimal price,
        String currency,
        boolean available,
        Instant timestamp,
        String extractionSource
) {}
