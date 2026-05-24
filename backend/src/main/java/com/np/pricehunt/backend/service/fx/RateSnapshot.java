package com.np.pricehunt.backend.service.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

public record RateSnapshot(LocalDate asOf, Map<String, BigDecimal> rates) {}
