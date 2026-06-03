package com.np.pricehunt.backend.dto;

import com.np.pricehunt.backend.domain.ExtractionSource;
import java.math.BigDecimal;

public record PriceInfo(BigDecimal price, String currency, boolean available, ExtractionSource extractionSource) {}
