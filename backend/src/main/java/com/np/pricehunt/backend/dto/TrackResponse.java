package com.np.pricehunt.backend.dto;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.ShopNameSource;
import java.time.Instant;

/**
 * The outcome of tracking or refreshing a URL: the listing, and the price this scrape observed.
 *
 * <p>{@code currentPrice} is a decimal string — see {@link DashboardProductResponse} for why money
 * never crosses this boundary as a JSON number. This is the one response built from a record that
 * has not yet round-tripped Postgres, which is why {@code WireMoney} formats at a fixed scale: the
 * price here and the same row read back from price-history must be the same string.
 */
public record TrackResponse(
        Long productId,
        String productName,
        Long trackedItemId,
        String url,
        String shopName,
        ShopNameSource shopNameSource,
        String currentPrice,
        String currency,
        AvailabilityStatus availability,
        Instant lastChecked,
        ExtractionSource extractionSource) {}
