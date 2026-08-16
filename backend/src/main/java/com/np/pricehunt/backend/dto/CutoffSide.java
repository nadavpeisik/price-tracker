package com.np.pricehunt.backend.dto;

/**
 * Which of the dashboard's two evaluation instants a {@link CutoffObservationRow} was selected for
 * (issue #146).
 *
 * <p>{@code delta7d} compares a product's best offer <em>now</em> against its best offer at {@code
 * now − 7d}. Both sides need each listing's latest eligible observation at their own cutoff, so the
 * query fetches two windows in one pass and tags every row with the window it came from.
 *
 * <p>The two windows overlap whenever the carry-forward TTL is at least 7 days, so one record can
 * legitimately be selected for both sides. It is then returned twice, once per side — which is why
 * consumers must key on {@code (trackedItemId, side)} and never on the record id alone.
 */
public enum CutoffSide {
    /** Latest eligible observation at the request instant — drives the headline price and availability. */
    CURRENT,
    /** Latest eligible observation seven days earlier — the delta's denominator. */
    BASELINE
}
