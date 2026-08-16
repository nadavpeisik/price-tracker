package com.np.pricehunt.backend.service.dashboard;

import com.np.pricehunt.backend.config.PriceTrendProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.dto.AvailabilityRollupStatus;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.projection.CutoffObservationRow;
import com.np.pricehunt.backend.repository.projection.CutoffSide;
import com.np.pricehunt.backend.repository.projection.DashboardListingRef;
import com.np.pricehunt.backend.repository.projection.TrendRecordView;
import com.np.pricehunt.backend.service.dashboard.ProductDashboardSnapshot.AvailabilitySummary;
import com.np.pricehunt.backend.service.fx.HistoricalRateRequirements;
import com.np.pricehunt.backend.service.fx.HistoricalRateWindow;
import com.np.pricehunt.backend.service.fx.HistoricalRateWindowLoader;
import com.np.pricehunt.backend.service.trend.ListingWindow;
import com.np.pricehunt.backend.service.trend.PriceTrendCalculator;
import com.np.pricehunt.backend.service.trend.ProductTrend;
import com.np.pricehunt.backend.service.trend.TrendPoint;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Computes every tracked product's headline price, availability rollup and 7-day delta in one pass
 * (issue #146).
 *
 * <p><b>Why a lean pass exists at all.</b> The dashboard's two summary tiles aggregate {@code delta7d}
 * over the <em>whole</em> tracked set — "3 price drops this week", "biggest drop: −18%" — so the delta
 * cannot be computed for the visible page only. Running the trend engine over every product would
 * fetch each listing's full multi-week history just to throw the daily series away. This pass fetches
 * only the records the delta actually reads: each listing's latest observation at each of the two
 * cutoffs, at most two rows per listing, in a single query.
 *
 * <p><b>What it shares with the trend engine, and why.</b> Only the <em>fetch</em> differs. This
 * calls {@link PriceTrendCalculator#compute} — the very operation behind the sparkline endpoint — with
 * a one-day window, so offer selection, eligibility, FX conversion and the delta arithmetic are not
 * merely equivalent but identical. A dashboard row and that product's trend endpoint therefore agree
 * by construction, which is what {@code DashboardQueryIntegrationTest} pins across the awkward
 * fixtures (UNAVAILABLE latest, TTL-expired, timestamp ties, stale rates).
 *
 * <p><b>Documented divergence from {@code GET /api/products}.</b> That endpoint's rollup reads each
 * listing's raw latest record at any age. Here an observation older than the carry-forward TTL counts
 * as "not checked" and rolls up to UNKNOWN, and {@code mixedCurrencies} sees TTL-windowed records
 * only. This is the more honest answer — a month-old "in stock" is not evidence of anything — and it
 * follows from the TTL-bounded fetch. It is transitional either way: {@code getAllProducts} is
 * deprecated for removal once the frontend cuts over.
 */
@Service
@RequiredArgsConstructor
public class DashboardSnapshotService {

    private final PriceRecordRepository priceRecordRepository;
    private final HistoricalRateWindowLoader rateWindowLoader;
    private final PriceTrendCalculator calculator;
    private final PriceTrendProperties trendProperties;

    /**
     * @param listingsByProductId every product to snapshot, including those with no listings (they
     *     get an empty rollup rather than being dropped from the result)
     * @param asOf evaluation instant, captured once by the caller so every product in the response
     *     describes the same moment
     * @param displayCurrency already validated by the caller
     * @return one snapshot per input product, iteration-ordered like the input
     */
    public Map<Long, ProductDashboardSnapshot> snapshotAll(
            Map<Long, List<DashboardListingRef>> listingsByProductId, Instant asOf, String displayCurrency) {

        int carryForwardDays = trendProperties.carryForwardDays();
        Instant baselineCutoff = asOf.minus(PriceTrendCalculator.DELTA_WINDOW_DAYS, ChronoUnit.DAYS);
        Instant currentCarryForwardFloor = asOf.minus(carryForwardDays, ChronoUnit.DAYS);
        Instant baselineCarryForwardFloor = baselineCutoff.minus(carryForwardDays, ChronoUnit.DAYS);

        Map<Long, ListingCutoffObservations> observationsByListingId =
                collectByListingId(priceRecordRepository.findCutoffObservations(
                        currentCarryForwardFloor, asOf, baselineCarryForwardFloor, baselineCutoff));

        HistoricalRateWindow baselineRates =
                loadBaselineRates(observationsByListingId.values(), baselineCutoff, displayCurrency);

        Map<Long, ProductDashboardSnapshot> snapshotsByProductId = new LinkedHashMap<>();
        listingsByProductId.forEach((productId, listings) -> snapshotsByProductId.put(
                productId,
                snapshotProduct(
                        productId,
                        listings == null ? List.<DashboardListingRef>of() : listings,
                        observationsByListingId,
                        asOf,
                        displayCurrency,
                        baselineRates,
                        carryForwardDays)));
        return snapshotsByProductId;
    }

    private ProductDashboardSnapshot snapshotProduct(
            Long productId,
            List<DashboardListingRef> listings,
            Map<Long, ListingCutoffObservations> observationsByListingId,
            Instant asOf,
            String displayCurrency,
            HistoricalRateWindow baselineRates,
            int carryForwardDays) {

        List<ListingWindow> listingWindows = new ArrayList<>(listings.size());
        for (DashboardListingRef listing : listings) {
            ListingCutoffObservations observations = observationsByListingId.get(listing.trackedItemId());
            listingWindows.add(new ListingWindow(
                    listing.trackedItemId(),
                    listing.shopName(),
                    observations == null ? List.of() : observations.toChronologicalRecords()));
        }

        // A single-day window: the calculator's day loop runs exactly once, takes its "today" branch,
        // and emits at most the one point this row needs. The dashboard and the trend endpoint agree
        // because they call the same operation, not because two of them share a helper.
        LocalDate asOfDate = LocalDate.ofInstant(asOf, ZoneOffset.UTC);
        ProductTrend trend =
                calculator.compute(listingWindows, asOfDate, asOf, displayCurrency, baselineRates, carryForwardDays);
        TrendPoint headline = trend.points().isEmpty() ? null : trend.points().get(0);

        // Provenance for the winning offer comes from its CURRENT-side row: the as-listed price and
        // currency the user sees under the converted headline.
        CutoffObservationRow headlineObservation = headline == null
                ? null
                : currentObservationFor(
                        observationsByListingId, headline.bestOffer().trackedItemId());

        return new ProductDashboardSnapshot(
                productId,
                headline == null ? null : headline.price(),
                headlineObservation == null ? null : headlineObservation.getPrice(),
                headlineObservation == null ? null : headlineObservation.getCurrency(),
                headline == null ? null : headline.bestOffer().shopName(),
                trend.conversionAsOf(),
                trend.conversionStale(),
                hasMixedCurrencies(listings, observationsByListingId),
                summarizeAvailability(listings, observationsByListingId),
                trend.delta7d());
    }

    /**
     * Groups the query's rows by listing, keeping the two sides apart.
     *
     * <p>Side membership is preserved <em>before</em> anything is deduplicated, because the two are
     * not interchangeable: when the windows overlap the same record arrives twice, and the CURRENT
     * copy is what availability, {@code mixedCurrencies} and the as-listed price all read. Collapsing
     * on record id first would sometimes leave only the BASELINE copy and silently blank those fields.
     */
    private static Map<Long, ListingCutoffObservations> collectByListingId(List<CutoffObservationRow> rows) {
        Map<Long, ListingCutoffObservations> byListingId = new HashMap<>();
        for (CutoffObservationRow row : rows) {
            byListingId
                    .computeIfAbsent(row.getTrackedItemId(), id -> new ListingCutoffObservations())
                    .accept(row);
        }
        return byListingId;
    }

    private HistoricalRateWindow loadBaselineRates(
            Iterable<ListingCutoffObservations> observations, Instant baselineCutoff, String displayCurrency) {

        // Only the baseline side consults historical rates; the current side converts through the live
        // snapshot (see PriceTrendCalculator's class javadoc for why). One day is enough because the
        // loader's anchor query resolves the newest rate at or before the window start.
        LocalDate baselineDay = LocalDate.ofInstant(baselineCutoff, ZoneOffset.UTC);

        Set<String> observedCurrencies = new HashSet<>();
        observations.forEach(listing -> listing.forEach(row -> observedCurrencies.add(row.getCurrency())));

        return rateWindowLoader.load(
                baselineDay,
                baselineDay,
                HistoricalRateRequirements.forConversion(observedCurrencies, displayCurrency));
    }

    private static CutoffObservationRow currentObservationFor(
            Map<Long, ListingCutoffObservations> byListingId, long trackedItemId) {
        ListingCutoffObservations observations = byListingId.get(trackedItemId);
        return observations == null ? null : observations.current();
    }

    /**
     * Whether the product's listings span more than one currency.
     *
     * <p>Computed before eligibility, mirroring {@code ProductQueryService}: the flag describes the
     * <em>catalogue</em> ("these shops quote in different currencies, we normalized them"), not the
     * subset that happens to be buyable right now — so an out-of-stock USD listing still makes an
     * otherwise-ILS product mixed.
     */
    private static boolean hasMixedCurrencies(
            List<DashboardListingRef> listings, Map<Long, ListingCutoffObservations> observationsByListingId) {

        String firstCurrency = null;
        for (DashboardListingRef listing : listings) {
            CutoffObservationRow current = currentObservationFor(observationsByListingId, listing.trackedItemId());
            if (current == null || current.getCurrency() == null) {
                continue;
            }
            if (firstCurrency == null) {
                firstCurrency = current.getCurrency();
            } else if (!firstCurrency.equals(current.getCurrency())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Product-level availability over <em>all</em> listings.
     *
     * <p>A listing with no observation in the TTL window counts as UNKNOWN, not as absent: "we
     * haven't checked this shop recently" is information the badge should reflect, and dropping such
     * listings from the denominator would make "2 of 2 in stock" out of five tracked shops.
     */
    private static AvailabilitySummary summarizeAvailability(
            List<DashboardListingRef> listings, Map<Long, ListingCutoffObservations> observationsByListingId) {

        int total = listings.size();
        if (total == 0) {
            return AvailabilitySummary.nothingTracked();
        }

        int available = 0;
        int unavailable = 0;
        for (DashboardListingRef listing : listings) {
            CutoffObservationRow current = currentObservationFor(observationsByListingId, listing.trackedItemId());
            AvailabilityStatus status = current == null ? AvailabilityStatus.UNKNOWN : current.getAvailability();
            if (status == AvailabilityStatus.AVAILABLE) {
                available++;
            } else if (status == AvailabilityStatus.UNAVAILABLE) {
                unavailable++;
            }
        }

        AvailabilityRollupStatus status;
        if (available == total) {
            status = AvailabilityRollupStatus.AVAILABLE;
        } else if (unavailable == total) {
            status = AvailabilityRollupStatus.UNAVAILABLE;
        } else if (available > 0) {
            status = AvailabilityRollupStatus.MIXED;
        } else {
            // No listing in stock, and at least one unknown — never claim UNAVAILABLE on a guess.
            status = AvailabilityRollupStatus.UNKNOWN;
        }
        return new AvailabilitySummary(status, available, total);
    }

    /**
     * One listing's selected observations, one per cutoff.
     *
     * <p>A mutable holder rather than a record because it is filled row by row as the flat result set
     * is walked, and either side may legitimately be missing.
     */
    private static final class ListingCutoffObservations {

        private static final Comparator<CutoffObservationRow> OLDEST_FIRST = Comparator.comparing(
                        CutoffObservationRow::getObservedAt)
                .thenComparing(CutoffObservationRow::getRecordId);

        private CutoffObservationRow current;
        private CutoffObservationRow baseline;

        void accept(CutoffObservationRow row) {
            if (row.getSide() == CutoffSide.CURRENT) {
                current = row;
            } else {
                baseline = row;
            }
        }

        CutoffObservationRow current() {
            return current;
        }

        void forEach(Consumer<CutoffObservationRow> action) {
            if (baseline != null) {
                action.accept(baseline);
            }
            if (current != null) {
                action.accept(current);
            }
        }

        /**
         * The two observations as a {@link ListingWindow}'s record list: deduplicated and <b>sorted
         * oldest-first</b>.
         *
         * <p>Both parts matter. When the windows overlap, one record is selected by both sides and
         * would otherwise appear twice. And ascending order is {@code ListingWindow}'s documented
         * contract — the calculator scans from the tail to find "latest at or before", so a list that
         * happened to arrive current-first would hand it the baseline as the current price. Native
         * query result order is unspecified, so this cannot be left to chance.
         */
        List<TrendRecordView> toChronologicalRecords() {
            List<CutoffObservationRow> selected = new ArrayList<>(2);
            if (baseline != null) {
                selected.add(baseline);
            }
            if (current != null && (baseline == null || !current.getRecordId().equals(baseline.getRecordId()))) {
                selected.add(current);
            }
            selected.sort(OLDEST_FIRST);

            List<TrendRecordView> records = new ArrayList<>(selected.size());
            for (CutoffObservationRow row : selected) {
                records.add(new TrendRecordView(
                        row.getTrackedItemId(),
                        row.getPrice(),
                        row.getCurrency(),
                        row.getAvailability(),
                        row.getObservedAt()));
            }
            return records;
        }
    }
}
