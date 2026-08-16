package com.np.pricehunt.backend.dto;

/**
 * A product's availability as one dashboard row reports it (issue #146).
 *
 * <p>Counts accompany the status because MIXED is not renderable alone — the badge reads "3 of 5 in
 * stock". {@code total} can legitimately be 0 (a product with no listings yet), which the UI shows as
 * "No shops tracked" rather than "0 of 0 in stock".
 */
public record DashboardAvailabilityResponse(AvailabilityRollupStatus status, int availableCount, int total) {}
