package com.np.pricehunt.backend.dto;

/**
 * The figures behind the dashboard's summary tiles (issue #146).
 *
 * <p>Returned twice per response — once for the whole tracked set and once for the active query — so
 * the tiles can stay standing while the view below them narrows.
 *
 * @param drops7d products whose 7-day delta is negative; a null delta ("New") is not a drop, and
 *     neither is a flat 0
 * @param biggestDrop the single largest fall, or null when nothing fell — never a rise reported as a
 *     "drop"
 */
public record DashboardSummary(long totalTracked, long drops7d, DashboardBiggestDrop biggestDrop) {}
