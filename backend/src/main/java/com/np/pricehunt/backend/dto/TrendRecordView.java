package com.np.pricehunt.backend.dto;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Flat projection of a {@link com.np.pricehunt.backend.domain.PriceRecord} for the price-trend
 * engine (issue #145).
 *
 * <p>Carries only what the calculator needs, and deliberately keeps {@code trackedItemId} as a raw
 * id rather than the entity: the batch query spans many listings, and hydrating {@code TrackedItem}
 * per row would both re-issue selects and drag in the lazy {@code priceHistory} collection that
 * Lombok's {@code @Data} makes a foot-gun.
 */
public record TrendRecordView(
        Long trackedItemId, BigDecimal price, String currency, AvailabilityStatus availability, Instant timestamp) {}
