package com.np.pricehunt.backend.service.dashboard;

import com.np.pricehunt.backend.dto.AvailabilityRollupStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Everything one dashboard row shows about a product, computed in the whole-set lean pass (issue
 * #146).
 *
 * <p>One record per product rather than several parallel maps, because these values must agree with
 * each other: the headline price, the currency it was originally listed in, the shop it came from and
 * the conversion metadata all describe <em>the same winning listing</em>. Splitting them apart would
 * let a later refactor pair one product's price with another's shop.
 *
 * <p>It is also what the sort comparators read. Sorting on the same snapshot the row renders is what
 * guarantees "cheapest first" orders by the number the user actually sees.
 *
 * <p>The whole best-price cluster is null together when no listing has an eligible, convertible
 * observation — "not checked recently enough to say", which the UI renders as a neutral placeholder
 * rather than a zero.
 *
 * @param bestPriceConverted cheapest eligible offer in the display currency
 * @param bestPriceOriginal that same offer as listed, before conversion
 * @param bestTrackedItemId the listing behind {@code bestPriceShop} — the id the expanded panel
 *     marks "Best" (#157); null together with the rest of the cluster
 * @param mixedCurrencies the product's listings are not all priced in one currency — an info flag,
 *     not a warning; the comparison is still valid because everything is FX-normalized
 * @param delta7d percent change against seven days ago, or null for "less than a week of history"
 */
public record ProductDashboardSnapshot(
        Long productId,
        BigDecimal bestPriceConverted,
        BigDecimal bestPriceOriginal,
        String bestPriceOriginalCurrency,
        String bestPriceShop,
        Long bestTrackedItemId,
        LocalDate conversionAsOf,
        boolean conversionStale,
        boolean mixedCurrencies,
        AvailabilitySummary availability,
        BigDecimal delta7d) {

    /**
     * The product's availability rollup plus the raw counts behind it.
     *
     * <p>The counts travel with the status because MIXED alone is not renderable — the badge reads
     * "3 of 5 in stock", and recomputing that on the client would mean shipping every listing's state
     * to a row that deliberately does not carry its listings.
     */
    public record AvailabilitySummary(AvailabilityRollupStatus status, int availableCount, int total) {

        private static final AvailabilitySummary NOTHING_TRACKED =
                new AvailabilitySummary(AvailabilityRollupStatus.UNKNOWN, 0, 0);

        /** A product with no listings at all: "No shops tracked", not "0 of 0 in stock". */
        public static AvailabilitySummary nothingTracked() {
            return NOTHING_TRACKED;
        }
    }
}
