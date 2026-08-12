package com.np.pricehunt.backend.service.trend;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One day of the product-level series: the best FX-normalized price across the product's listings,
 * stamped at that UTC day's midnight, with the listing that supplied it.
 */
public record TrendPoint(Instant t, BigDecimal price, BestOffer bestOffer) {}
