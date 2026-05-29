package com.np.pricehunt.backend.service.fx;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ConvertedAmount(BigDecimal value, LocalDate asOf, boolean stale) {}
