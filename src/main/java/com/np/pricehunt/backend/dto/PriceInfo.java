package com.np.pricehunt.backend.dto;

import java.math.BigDecimal;

public record PriceInfo(
        BigDecimal price,
        String currency,
        boolean available
) {}
