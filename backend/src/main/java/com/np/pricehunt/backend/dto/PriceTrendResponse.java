package com.np.pricehunt.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A product's price trend in one display currency (issue #145).
 *
 * <p>{@code sparkline} is chronological and already normalized, so a client never merges per-listing
 * histories or converts currencies itself. Days with no eligible, convertible listing are absent
 * rather than zero-filled.
 *
 * <p>{@code delta7d} is a percentage (scale 2), or {@code null} when no trustworthy 7-day comparison
 * exists — under a week of history, no current price, a baseline too old to trust, or a stale rate on
 * either side. Null means "unknown" and renders as a "New" tag; it never means 0%.
 *
 * <p>{@code conversionAsOf} / {@code conversionStale} describe the <b>latest point's</b> conversion
 * only — deliberately the same meaning these fields carry on a dashboard row, which is why they share
 * its names. Staleness on older points does not aggregate into them; a stale conversion on either
 * side of the delta nulls {@code delta7d} instead.
 */
public record PriceTrendResponse(
        Long productId,
        String displayCurrency,
        BigDecimal delta7d,
        LocalDate conversionAsOf,
        boolean conversionStale,
        List<TrendPointResponse> sparkline) {}
