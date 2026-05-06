package com.np.pricehunt.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public record PricePointResponse(
        BigDecimal price,
        String currency,
        boolean available,
        LocalDateTime timestamp,
        String extractionSource
) {}
