package com.np.pricehunt.backend.service.trend;

import com.np.pricehunt.backend.repository.projection.TrendRecordView;
import com.np.pricehunt.backend.service.fx.ConvertedAmount;
import com.np.pricehunt.backend.service.fx.HistoricalRateWindow;
import com.np.pricehunt.backend.service.fx.PriceConverter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Turns a product's per-listing price histories into one FX-normalized daily series and a 7-day
 * delta (issue #145).
 *
 * <p><b>The daily rule.</b> For each UTC day, every listing contributes its latest observation at or
 * before that day's end — carried forward through scrape gaps for a bounded TTL, skipping listings
 * whose latest observation says UNAVAILABLE (see {@link TrendEligibility}). Each contribution is
 * converted to the display currency at <em>that day's</em> nearest-earlier rate, and the day's point
 * is the cheapest. A day where no listing qualifies emits no point rather than a guessed one.
 *
 * <p><b>Why the delta ignores the requested window.</b> {@code delta7d} is not read off the daily
 * series; it is two independent evaluations at exactly {@code now} and {@code now − 7d}. Deriving it
 * from day buckets would make a "7-day change" mean different things at different sparkline zoom
 * levels, and would let a day-bucketed baseline absorb prices up to 24 hours newer than the cutoff.
 *
 * <p><b>Why "current" uses the live snapshot.</b> Today's point and the delta's current side convert
 * through the snapshot path that {@code DashboardSnapshotService} uses for the dashboard row, not
 * through historical floors. The snapshot holds a single latest date, so a partially published date
 * could otherwise let per-quote floors resurrect a rate the row's path doesn't have — and the row and
 * the series would silently disagree. They are also computed once and reused, so today's point and the
 * delta's current value cannot diverge within one response.
 *
 * <p><b>Display-currency invariance (for #146) — conditional.</b> Which listing wins a given day is
 * normally independent of the display currency: every listing is multiplied by the same target rate,
 * which cancels in the comparison, leaving the EUR-normalized prices to decide. Two caveats keep this
 * from being unconditional, and a future materialized "daily best" projection must respect them
 * before assuming one row per product-day suffices:
 *
 * <ul>
 *   <li><b>A nonzero FX margin breaks it.</b> The margin models a foreign-transaction fee, so {@link
 *       PriceConverter} deliberately does not apply it when a listing is already in the display
 *       currency. That means the margin lands on <em>different</em> listings depending on which
 *       currency was requested, and with a large enough margin the winner can flip. It cancels — and
 *       invariance holds — only while {@code pricehunt.currency.fx-margin-percent} is zero, which is
 *       the default.
 *   <li><b>Rounding can tie-break differently.</b> Comparison happens on values already rounded to the
 *       converter's output scale, so listings within a rounding step of each other can swap order
 *       between currencies.
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceTrendCalculator {

    /**
     * Not configurable: "7-day delta" is the feature's semantics, not a tuning knob.
     *
     * <p>Public because the dashboard's two-cutoff query has to derive the same baseline instant in
     * SQL; a second literal 7 there would be a silent way for the two paths to drift apart.
     */
    public static final int DELTA_WINDOW_DAYS = 7;

    private static final int DELTA_INTERMEDIATE_SCALE = 12;
    private static final int DELTA_OUTPUT_SCALE = 2;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final PriceConverter priceConverter;

    /**
     * @param listings each listing's records, oldest-first; treated as read-only, so callers may pass
     *     an immutable list
     * @param windowStartDay first day of the sparkline window (inclusive)
     * @param now evaluation instant, from the injected clock
     * @param displayCurrency already validated by the caller
     * @param rates historical rates covering the window and the delta baseline
     * @param carryForwardDays freshness TTL, shared with the dashboard row's eligibility
     */
    public ProductTrend compute(
            List<ListingWindow> listings,
            LocalDate windowStartDay,
            Instant now,
            String displayCurrency,
            HistoricalRateWindow rates,
            int carryForwardDays) {

        if (listings == null || listings.isEmpty() || windowStartDay == null || now == null) {
            return ProductTrend.empty();
        }

        LocalDate today = LocalDate.ofInstant(now, ZoneOffset.UTC);
        ComputationContext context = new ComputationContext(displayCurrency, rates, carryForwardDays);

        // Computed once, used for both today's point and the delta's current side.
        BestOfferCandidate current = bestOfferAsOf(listings, now, null, context);

        Instant baselineInstant = now.minus(DELTA_WINDOW_DAYS, ChronoUnit.DAYS);
        BestOfferCandidate baseline =
                bestOfferAsOf(listings, baselineInstant, LocalDate.ofInstant(baselineInstant, ZoneOffset.UTC), context);

        List<TrendPoint> points = new ArrayList<>();
        BestOfferCandidate latestEmitted = null;
        List<ListingHistoryCursor> cursors =
                listings.stream().map(ListingHistoryCursor::new).toList();

        for (LocalDate day = windowStartDay; !day.isAfter(today); day = day.plusDays(1)) {
            BestOfferCandidate best = day.equals(today) ? current : bestOfferForCompletedDay(cursors, day, context);
            if (best != null) {
                points.add(new TrendPoint(
                        day.atStartOfDay(ZoneOffset.UTC).toInstant(),
                        best.displayPrice(),
                        new BestOffer(best.trackedItemId(), best.shopName(), best.observedAt())));
                latestEmitted = best;
            }
        }

        if (context.nonPositivePriceSeen()) {
            log.warn("Ignored non-positive price record(s) while computing a price trend; check for manual DB edits");
        }

        return new ProductTrend(
                List.copyOf(points),
                delta(current, baseline),
                latestEmitted == null ? null : latestEmitted.conversion().asOf(),
                latestEmitted != null && latestEmitted.conversion().stale());
    }

    /**
     * Percent change from the baseline, or null when no trustworthy comparison exists: no current
     * price, no eligible observation around {@code now − 7d} (which also covers "less than a week of
     * history" and long outages, because the TTL bounds how old the baseline observation may be), a
     * zero baseline, or a stale rate on either side. Null renders as "New" — never as 0%.
     */
    private static BigDecimal delta(BestOfferCandidate current, BestOfferCandidate baseline) {
        if (current == null || baseline == null || baseline.displayPrice().signum() == 0) {
            return null;
        }
        if (current.conversion().stale() || baseline.conversion().stale()) {
            return null;
        }
        return current.displayPrice()
                .subtract(baseline.displayPrice())
                .divide(baseline.displayPrice(), DELTA_INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
                .multiply(HUNDRED)
                .setScale(DELTA_OUTPUT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Cheapest eligible listing as of an exact instant — each listing's latest record at or before
     * {@code inclusiveCutoff}. A null {@code valuationDate} selects the live-snapshot conversion path;
     * a non-null one selects historical per-quote floors at that date.
     *
     * <p>Taking exactly <b>one</b> record per cutoff is mirrored in SQL by {@code
     * PriceRecordRepository.findCutoffObservations}. Adding a fallback past an ineligible record would
     * make that query silently under-fetch for the dashboard.
     */
    private BestOfferCandidate bestOfferAsOf(
            List<ListingWindow> listings,
            Instant inclusiveCutoff,
            LocalDate valuationDate,
            ComputationContext context) {

        BestOfferCandidate best = null;
        for (ListingWindow listing : listings) {
            TrendRecordView candidate = latestAtOrBefore(listing.records(), inclusiveCutoff);
            best = chooseBetterOffer(
                    best, toBestOfferCandidate(listing, candidate, inclusiveCutoff, valuationDate, context));
        }
        return best;
    }

    /**
     * Cheapest eligible listing for one completed day. The selection bound is the following midnight
     * <em>exclusive</em> so a record stamped exactly at midnight belongs to one day only, while the
     * value is converted at the day's own rate — the cutoff and the valuation date are deliberately
     * different instants.
     *
     * <p>Callers must pass days in ascending order: each cursor only advances, which is what keeps the
     * whole window O(records + days).
     */
    private BestOfferCandidate bestOfferForCompletedDay(
            List<ListingHistoryCursor> cursors, LocalDate day, ComputationContext context) {

        Instant exclusiveEnd = day.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        BestOfferCandidate best = null;

        for (ListingHistoryCursor cursor : cursors) {
            TrendRecordView candidate = cursor.advanceAndGetLatestBefore(exclusiveEnd);
            best = chooseBetterOffer(
                    best, toBestOfferCandidate(cursor.listing(), candidate, exclusiveEnd, day, context));
        }
        return best;
    }

    /**
     * Turns one listing's candidate record into a comparable offer, or null when the record is
     * ineligible or cannot be converted to the display currency.
     */
    private BestOfferCandidate toBestOfferCandidate(
            ListingWindow listing,
            TrendRecordView candidate,
            Instant eligibilityInstant,
            LocalDate valuationDate,
            ComputationContext context) {

        if (candidate == null) {
            return null;
        }
        if (candidate.price() != null && candidate.price().signum() <= 0) {
            context.markNonPositivePriceSeen();
        }
        if (!TrendEligibility.isEligible(
                candidate.timestamp(),
                candidate.availability(),
                candidate.price(),
                eligibilityInstant,
                context.carryForwardDays())) {
            return null;
        }

        ConvertedAmount conversion = valuationDate == null
                ? priceConverter.convert(candidate.price(), candidate.currency(), context.displayCurrency())
                : priceConverter.convert(
                        candidate.price(),
                        candidate.currency(),
                        context.displayCurrency(),
                        valuationDate,
                        context.rates());
        if (conversion == null) {
            // Unconvertible (no rate that early, unknown currency) — this listing simply doesn't
            // participate; other listings can still supply the day's price.
            return null;
        }
        return new BestOfferCandidate(
                conversion.value(), conversion, listing.trackedItemId(), listing.shopName(), candidate.timestamp());
    }

    /** Cheapest wins; equal converted prices are broken by listing id so the winner is stable. */
    private static BestOfferCandidate chooseBetterOffer(BestOfferCandidate incumbent, BestOfferCandidate challenger) {
        if (challenger == null) {
            return incumbent;
        }
        if (incumbent == null) {
            return challenger;
        }
        int byPrice = challenger.displayPrice().compareTo(incumbent.displayPrice());
        if (byPrice < 0) {
            return challenger;
        }
        if (byPrice == 0 && challenger.trackedItemId() < incumbent.trackedItemId()) {
            return challenger;
        }
        return incumbent;
    }

    /**
     * Latest record at or before {@code cutoff}; records are ascending, so scan back from the end.
     *
     * <p>Timestamps are dereferenced without a null check here and in the cursor walk: {@code
     * price_record.timestamp} is NOT NULL in the schema, so a null would mean the projection or the
     * database is broken, and failing loudly beats silently mis-pricing a listing.
     */
    private static TrendRecordView latestAtOrBefore(List<TrendRecordView> records, Instant cutoff) {
        for (int i = records.size() - 1; i >= 0; i--) {
            TrendRecordView observation = records.get(i);
            if (!observation.timestamp().isAfter(cutoff)) {
                return observation;
            }
        }
        return null;
    }

    /**
     * Everything scoped to one {@link #compute} call: the conversion target, the rates to convert with,
     * how long a price carries forward, and the single piece of state the computation accumulates —
     * whether any corrupt price was seen.
     *
     * <p>Grouped so the per-listing helpers take the two things that actually vary — which listing, and
     * which point in time — instead of re-threading four unchanging arguments through every call. A
     * fresh instance per call is also what stops the warning leaking between products in a batch.
     *
     * <p>A class rather than a record: three of the four fields are fixed configuration, but the
     * corrupt-price flag is accumulated as the computation runs, and a record would advertise a
     * value-like immutability this does not have.
     */
    private static final class ComputationContext {

        private final String displayCurrency;
        private final HistoricalRateWindow rates;
        private final int carryForwardDays;
        private boolean nonPositivePriceSeen;

        ComputationContext(String displayCurrency, HistoricalRateWindow rates, int carryForwardDays) {
            this.displayCurrency = displayCurrency;
            this.rates = rates;
            this.carryForwardDays = carryForwardDays;
        }

        String displayCurrency() {
            return displayCurrency;
        }

        HistoricalRateWindow rates() {
            return rates;
        }

        int carryForwardDays() {
            return carryForwardDays;
        }

        /** Latched, not counted: one warn per computation, so corrupt rows can't spam the log. */
        void markNonPositivePriceSeen() {
            nonPositivePriceSeen = true;
        }

        boolean nonPositivePriceSeen() {
            return nonPositivePriceSeen;
        }
    }

    /**
     * One listing's offer, comparable against the other listings' — a <em>candidate</em> because it is
     * built for every listing and most of them lose. The winner becomes the point's {@link BestOffer}.
     *
     * <p>{@code conversion} is retained alongside {@code displayPrice} because the winner's staleness
     * and as-of date are what the response reports.
     */
    private record BestOfferCandidate(
            BigDecimal displayPrice,
            ConvertedAmount conversion,
            long trackedItemId,
            String shopName,
            Instant observedAt) {}

    /**
     * A listing's record list plus a bookmark into it, used to walk the window one day at a time.
     *
     * <p>The bookmark is the index of the first record <em>not yet passed</em>. Because the caller
     * feeds days in ascending order the bookmark only ever moves forward, so every record is examined
     * once across the whole window — O(records + days) rather than O(records × days).
     *
     * <p><b>Precondition:</b> successive {@code exclusiveEnd} values must be non-decreasing. Going
     * backwards silently returns a stale answer rather than rescanning.
     */
    private static final class ListingHistoryCursor {

        private final ListingWindow listing;
        private int nextUnpassed;

        ListingHistoryCursor(ListingWindow listing) {
            this.listing = listing;
        }

        ListingWindow listing() {
            return listing;
        }

        /**
         * The listing's newest record strictly before {@code exclusiveEnd}, or null when it has none
         * that early. Often a record from days earlier — that is carry-forward, and whether it is still
         * fresh enough to count is {@link TrendEligibility}'s decision, not this cursor's.
         */
        TrendRecordView advanceAndGetLatestBefore(Instant exclusiveEnd) {
            List<TrendRecordView> records = listing.records();
            while (nextUnpassed < records.size()
                    && records.get(nextUnpassed).timestamp().isBefore(exclusiveEnd)) {
                nextUnpassed++;
            }
            return nextUnpassed == 0 ? null : records.get(nextUnpassed - 1);
        }
    }
}
