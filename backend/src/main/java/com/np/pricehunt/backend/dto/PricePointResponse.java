package com.np.pricehunt.backend.dto;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import java.math.BigDecimal;
import java.time.Instant;

public record PricePointResponse(
        BigDecimal price,
        String currency,
        AvailabilityStatus availability,
        Instant timestamp,
        String extractionSource) {}
