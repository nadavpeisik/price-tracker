package com.np.pricehunt.backend.service.trend;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * A product's computed trend: the chronological daily series plus the 7-day delta.
 *
 * <p>{@code delta7d} is a percentage (scale 2) or {@code null} when no trustworthy comparison exists
 * — see {@link PriceTrendCalculator} for the exact conditions. Null means "we don't know", and the
 * dashboard renders it as a "New" tag; it never means zero.
 *
 * <p>{@code conversionAsOf} / {@code conversionStale} describe the <b>latest emitted point's</b>
 * winning conversion only, matching what the same-named fields mean on a dashboard row. Historical
 * staleness deliberately does not aggregate into them: a week-old rate on an old point must not badge
 * a freshly converted current price as outdated. A stale conversion on either side of the delta nulls
 * {@code delta7d} instead.
 */
public record ProductTrend(
        List<TrendPoint> points, BigDecimal delta7d, LocalDate conversionAsOf, boolean conversionStale) {

    public static ProductTrend empty() {
        return new ProductTrend(List.of(), null, null, false);
    }
}
