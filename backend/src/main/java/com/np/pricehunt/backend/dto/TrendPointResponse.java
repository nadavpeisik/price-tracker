package com.np.pricehunt.backend.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** One day of the FX-normalized product series, stamped at that UTC day's midnight. */
public record TrendPointResponse(Instant t, BigDecimal price, BestOfferResponse bestOffer) {}
