package com.np.pricehunt.backend.service.trend;

import com.np.pricehunt.backend.dto.TrendRecordView;
import java.util.List;

/**
 * One listing's price observations over the fetch window, ordered oldest-first.
 *
 * <p>Ascending order is a contract, not a convenience: {@link PriceTrendCalculator} walks each
 * listing with a single forward cursor as it steps through days, which is what keeps the whole
 * computation O(records + days).
 */
public record ListingWindow(long trackedItemId, String shopName, List<TrendRecordView> records) {}
