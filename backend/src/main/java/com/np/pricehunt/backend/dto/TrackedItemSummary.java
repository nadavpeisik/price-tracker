package com.np.pricehunt.backend.dto;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ShopNameSource;
import java.time.Instant;

/**
 * One shop's listing under a product, with its latest observed price.
 *
 * <p>{@code currentPrice} is a decimal string — see {@link DashboardProductResponse} for why money
 * never crosses this boundary as a JSON number. It is null when the listing has never been scraped
 * successfully, which the UI renders as a placeholder rather than a zero.
 */
public record TrackedItemSummary(
        Long id,
        String url,
        String shopName,
        ShopNameSource shopNameSource,
        String currentPrice,
        String currency,
        AvailabilityStatus availability,
        Instant lastChecked) {}
