package com.np.pricehunt.backend.dto;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import java.time.Instant;

/**
 * One observation in a tracked item's price history, as the shop listed it.
 *
 * <p>{@code price} is a decimal string — see {@link DashboardProductResponse} for why money never
 * crosses this boundary as a JSON number.
 */
public record PricePointResponse(
        String price, String currency, AvailabilityStatus availability, Instant observedAt, String extractionSource) {}
