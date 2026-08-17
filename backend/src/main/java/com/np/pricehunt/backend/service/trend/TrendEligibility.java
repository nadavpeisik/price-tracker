package com.np.pricehunt.backend.service.trend;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * The single definition of "this observation may count as a listing's price right now" (issue #145).
 *
 * <p>{@link PriceTrendCalculator} is the only caller, and that is the point: the dashboard row's
 * headline best price reaches this rule <em>through</em> the calculator (see {@link
 * com.np.pricehunt.backend.service.dashboard.DashboardSnapshotService}), so the row and the trend
 * series' latest point are the same number by shared code rather than by two implementations
 * happening to agree.
 *
 * <p>Four rules, each with a reason:
 *
 * <ul>
 *   <li><b>Not after the reference instant</b> — a future-dated record (clock skew, manual insert)
 *       must never shadow a valid earlier one.
 *   <li><b>Within the carry-forward TTL</b> — a price we saw and haven't contradicted still stands
 *       through scrape gaps, but only for a bounded time; a listing nobody has checked in weeks stops
 *       anchoring "best price". The bound is inclusive: exactly-TTL-old still counts.
 *   <li><b>Not UNAVAILABLE</b> — you can't buy at an out-of-stock price. Because callers only ever
 *       pass the <em>latest</em> record, this also means an UNAVAILABLE observation cancels that
 *       listing's carry-forward rather than letting an older in-stock price resurface. UNKNOWN stays
 *       eligible: it means extraction couldn't tell, not that the item is gone.
 *   <li><b>Positive price</b> — {@code PriceValidator} rejects non-positive prices at ingest, so this
 *       only guards hand-inserted rows; without it a corrupt row could win the row's best price while
 *       the trend series (which converts and would drop it) disagreed.
 * </ul>
 */
public final class TrendEligibility {

    private TrendEligibility() {}

    public static boolean isEligible(
            Instant timestamp, AvailabilityStatus availability, BigDecimal price, Instant reference, int ttlDays) {
        if (timestamp == null || reference == null || price == null) {
            return false;
        }
        if (timestamp.isAfter(reference)) {
            return false;
        }
        if (timestamp.isBefore(reference.minus(ttlDays, ChronoUnit.DAYS))) {
            return false;
        }
        if (availability == AvailabilityStatus.UNAVAILABLE) {
            return false;
        }
        return price.signum() > 0;
    }
}
