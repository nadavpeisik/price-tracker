package com.np.pricehunt.backend.service.trend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.config.CurrencyProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.repository.projection.TrendRecordView;
import com.np.pricehunt.backend.service.fx.ExchangeRateService;
import com.np.pricehunt.backend.service.fx.HistoricalRateWindow;
import com.np.pricehunt.backend.service.fx.PriceConverter;
import com.np.pricehunt.backend.service.fx.RateSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Pure-logic tests for the series/delta engine — no database, no Spring, no repositories. The only
 * collaborator is a real {@link PriceConverter} over a mocked rate service, so the arithmetic under
 * test is the arithmetic that runs in production.
 *
 * <p>Time is pinned at {@link #NOW}; every fixture is expressed as "N days before now", which is how
 * the rules themselves are phrased.
 */
@ExtendWith(MockitoExtension.class)
class PriceTrendCalculatorTest {

    private static final Instant NOW = Instant.parse("2026-03-20T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 20);
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final int TTL = 7;
    private static final String ILS = "ILS";
    private static final String USD = "USD";

    /** EUR-base: 1 EUR = 1.10 USD = 4.00 ILS, so USD→ILS is 4.00/1.10 ≈ 3.636364. */
    private static final Map<String, BigDecimal> RATES =
            Map.of(USD, new BigDecimal("1.10"), ILS, new BigDecimal("4.00"));

    @Mock
    private ExchangeRateService rateService;

    private PriceTrendCalculator calculator;

    @BeforeEach
    void setUp() {
        // Construct here, not as a field initializer: @Mock fields are injected after field init.
        calculator = new PriceTrendCalculator(converter("0"));
    }

    // --- Same-currency series ---

    @Test
    void singleListing_emitsOnePointPerDayWithTheListingsPrice() {
        ListingWindow ksp = listing(1, "KSP", rec(daysAgo(3), "100", ILS), rec(daysAgo(1), "90", ILS));

        ProductTrend trend = compute(List.of(ksp), 4, HistoricalRateWindow.empty());

        assertThat(trend.points())
                .extracting(TrendPoint::price)
                .containsExactly(price("100"), price("100"), price("90"), price("90"));
        assertThat(trend.points())
                .extracting(TrendPoint::t)
                .containsExactly(midnight(3), midnight(2), midnight(1), midnight(0));
    }

    @Test
    void carryForward_fillsDaysWithNoScrape() {
        ListingWindow ksp = listing(1, "KSP", rec(daysAgo(4), "100", ILS));

        ProductTrend trend = compute(List.of(ksp), 5, HistoricalRateWindow.empty());

        // Observed once, four days ago; still the listing's price for every day within the TTL.
        assertThat(trend.points()).hasSize(5);
        assertThat(trend.points()).extracting(TrendPoint::price).containsOnly(price("100"));
    }

    @Test
    void carryForward_expiresAfterTheTtlSoTheListingDropsOut() {
        ListingWindow stale = listing(1, "KSP", rec(daysAgo(20), "100", ILS));

        ProductTrend trend = compute(List.of(stale), 10, HistoricalRateWindow.empty());

        assertThat(trend.points()).isEmpty();
        assertThat(trend.delta7d()).isNull();
    }

    @Test
    void daysBeforeAnyObservation_emitNoPoint() {
        ListingWindow ksp = listing(1, "KSP", rec(daysAgo(2), "100", ILS));

        ProductTrend trend = compute(List.of(ksp), 5, HistoricalRateWindow.empty());

        // Window covers 5 days but the first observation is 2 days ago: 3 points, not 5.
        assertThat(trend.points()).hasSize(3);
        assertThat(trend.points().get(0).t()).isEqualTo(midnight(2));
    }

    @Test
    void multipleListings_takeTheCheapestPerDay() {
        ListingWindow ksp = listing(1, "KSP", rec(daysAgo(3), "420", ILS));
        ListingWindow ivory = listing(2, "Ivory", rec(daysAgo(3), "385", ILS));

        ProductTrend trend = compute(List.of(ksp, ivory), 3, HistoricalRateWindow.empty());

        assertThat(trend.points()).extracting(TrendPoint::price).containsOnly(price("385"));
        assertThat(trend.points())
                .allSatisfy(p -> assertThat(p.bestOffer().shopName()).isEqualTo("Ivory"));
    }

    @Test
    void aFailedScrapeOnTheCheapShopDoesNotSpikeTheSeries() {
        // KSP scraped daily at 420; Ivory last seen 3 days ago at 385 and silent since.
        ListingWindow ksp = listing(
                1, "KSP", rec(daysAgo(3), "420", ILS), rec(daysAgo(2), "420", ILS), rec(daysAgo(1), "420", ILS));
        ListingWindow ivory = listing(2, "Ivory", rec(daysAgo(3), "385", ILS));

        ProductTrend trend = compute(List.of(ksp, ivory), 4, HistoricalRateWindow.empty());

        assertThat(trend.points()).extracting(TrendPoint::price).containsOnly(price("385"));
    }

    // --- Availability ---

    @Test
    void unavailableLatestRecord_excludesTheListingAndCancelsCarryForward() {
        ListingWindow ksp = listing(
                1,
                "KSP",
                rec(daysAgo(4), "300", ILS, AvailabilityStatus.AVAILABLE),
                rec(daysAgo(2), "300", ILS, AvailabilityStatus.UNAVAILABLE));
        ListingWindow ivory = listing(2, "Ivory", rec(daysAgo(4), "400", ILS));

        ProductTrend trend = compute(List.of(ksp, ivory), 5, HistoricalRateWindow.empty());

        // Days 4 and 3: KSP available at 300 wins. From day 2 on it is out of stock and its older
        // in-stock price must not resurface, so Ivory's 400 takes over.
        assertThat(trend.points())
                .extracting(TrendPoint::price)
                .containsExactly(price("300"), price("300"), price("400"), price("400"), price("400"));
    }

    @Test
    void unknownAvailability_stillCounts() {
        // UNKNOWN means extraction couldn't tell, not that the item is gone.
        ListingWindow ksp = listing(1, "KSP", rec(daysAgo(2), "250", ILS, AvailabilityStatus.UNKNOWN));

        ProductTrend trend = compute(List.of(ksp), 3, HistoricalRateWindow.empty());

        assertThat(trend.points()).extracting(TrendPoint::price).containsOnly(price("250"));
    }

    @Test
    void nonPositivePrice_isIgnored() {
        ListingWindow corrupt = listing(1, "KSP", rec(daysAgo(2), "0", ILS));
        ListingWindow ivory = listing(2, "Ivory", rec(daysAgo(2), "500", ILS));

        ProductTrend trend = compute(List.of(corrupt, ivory), 3, HistoricalRateWindow.empty());

        assertThat(trend.points()).extracting(TrendPoint::price).containsOnly(price("500"));
    }

    // --- delta7d ---

    @Test
    void delta_isPercentChangeAgainstTheBaselineSevenDaysBack() {
        ListingWindow ksp = listing(1, "KSP", rec(daysAgo(8), "1000", ILS), rec(daysAgo(1), "900", ILS));

        ProductTrend trend = compute(List.of(ksp), 10, HistoricalRateWindow.empty());

        assertThat(trend.delta7d()).isEqualByComparingTo("-10.00");
    }

    @Test
    void delta_isNullWhenHistoryIsShorterThanSevenDays() {
        ListingWindow ksp = listing(1, "KSP", rec(daysAgo(5), "1000", ILS), rec(daysAgo(1), "800", ILS));

        ProductTrend trend = compute(List.of(ksp), 10, HistoricalRateWindow.empty());

        assertThat(trend.delta7d()).isNull();
        assertThat(trend.points()).isNotEmpty();
    }

    @Test
    void delta_baselineSampleExactlyAtSevenDaysAgo_counts() {
        Instant exactly = NOW.minus(7, ChronoUnit.DAYS);
        ListingWindow ksp = listing(1, "KSP", rec(exactly, "500", ILS), rec(daysAgo(1), "450", ILS));

        ProductTrend trend = compute(List.of(ksp), 10, HistoricalRateWindow.empty());

        assertThat(trend.delta7d()).isEqualByComparingTo("-10.00");
    }

    @Test
    void delta_baselineOlderThanTheTtl_isNull() {
        // A long outage: nothing observed anywhere near now−7d, so a "7-day" delta would be a lie.
        ListingWindow ksp = listing(1, "KSP", rec(daysAgo(30), "1000", ILS), rec(daysAgo(1), "900", ILS));

        ProductTrend trend = compute(List.of(ksp), 10, HistoricalRateWindow.empty());

        assertThat(trend.delta7d()).isNull();
    }

    @Test
    void delta_flatPriceIsZeroNotNull() {
        ListingWindow ksp = listing(1, "KSP", rec(daysAgo(9), "700", ILS), rec(daysAgo(1), "700", ILS));

        ProductTrend trend = compute(List.of(ksp), 10, HistoricalRateWindow.empty());

        assertThat(trend.delta7d()).isNotNull().isEqualByComparingTo("0.00");
    }

    @Test
    void delta_isIndependentOfTheRequestedSparklineWindow() {
        ListingWindow ksp = listing(1, "KSP", rec(daysAgo(8), "1000", ILS), rec(daysAgo(1), "900", ILS));

        // A 7-day sparkline does not reach the baseline day, yet the delta is still computed.
        ProductTrend narrow = compute(List.of(ksp), 7, HistoricalRateWindow.empty());
        ProductTrend wide = compute(List.of(ksp), 30, HistoricalRateWindow.empty());

        assertThat(narrow.delta7d()).isEqualByComparingTo("-10.00");
        assertThat(narrow.delta7d()).isEqualByComparingTo(wide.delta7d());
        assertThat(narrow.points()).hasSizeLessThan(wide.points().size());
    }

    @Test
    void delta_isNullWhenThereIsNoCurrentPrice() {
        // Observations exist around the baseline but nothing recent enough to price today.
        ListingWindow ksp = listing(1, "KSP", rec(daysAgo(8), "1000", ILS));

        ProductTrend trend = compute(List.of(ksp), 12, HistoricalRateWindow.empty());

        assertThat(trend.delta7d()).isNull();
        assertThat(trend.points()).isNotEmpty();
        assertThat(trend.points().get(trend.points().size() - 1).t()).isNotEqualTo(midnight(0));
    }

    @Test
    void emptyListings_produceAnEmptyTrend() {
        ProductTrend trend = compute(List.of(), 30, HistoricalRateWindow.empty());

        assertThat(trend.points()).isEmpty();
        assertThat(trend.delta7d()).isNull();
        assertThat(trend.conversionAsOf()).isNull();
        assertThat(trend.conversionStale()).isFalse();
    }

    // --- Alignment invariant: today's point exists iff there is a current price ---

    @Test
    void todaysPointIsPresentExactlyWhenACurrentPriceExists() {
        ListingWindow fresh = listing(1, "KSP", rec(daysAgo(1), "300", ILS));
        ListingWindow stale = listing(1, "KSP", rec(daysAgo(9), "300", ILS));

        assertThat(lastPoint(compute(List.of(fresh), 12, HistoricalRateWindow.empty()))
                        .t())
                .isEqualTo(midnight(0));

        // The stale listing still priced the days its observation was fresh for, but the carry-forward
        // has expired by today — so the series legitimately ends in the past and there is no current
        // price. That is the row-alignment invariant, not a gap.
        ProductTrend expired = compute(List.of(stale), 12, HistoricalRateWindow.empty());
        assertThat(expired.points()).isNotEmpty();
        assertThat(lastPoint(expired).t()).isNotEqualTo(midnight(0));
        assertThat(expired.delta7d()).isNull();
    }

    // --- Cross-currency ---

    @Test
    void crossCurrency_comparesConvertedValuesNotRawNumbers() {
        snapshotOn(TODAY);
        // $102 ≈ ₪370.9 beats ₪385 even though 102 < 385 is not the comparison being made.
        ListingWindow electra = listing(1, "Electra", rec(daysAgo(1), "385", ILS));
        ListingWindow amazon = listing(2, "Amazon", rec(daysAgo(1), "102", USD));

        ProductTrend trend =
                compute(List.of(electra, amazon), 2, ratesOn(TODAY, TODAY.minusDays(1), TODAY.minusDays(2)));

        assertThat(lastPoint(trend).bestOffer().shopName()).isEqualTo("Amazon");
        assertThat(lastPoint(trend).price()).isEqualByComparingTo("370.9091");
    }

    @Test
    void winnerIsTheSameWhicheverDisplayCurrencyIsRequested_whenNoFxMarginIsConfigured() {
        // With no margin (the default), the target rate multiplies every listing equally and cancels,
        // so only the EUR-normalized prices decide — see the margin caveat pinned below.
        snapshotOn(TODAY);
        ListingWindow electra = listing(1, "Electra", rec(daysAgo(1), "385", ILS));
        ListingWindow amazon = listing(2, "Amazon", rec(daysAgo(1), "102", USD));
        HistoricalRateWindow rates = ratesOn(TODAY, TODAY.minusDays(1), TODAY.minusDays(2));

        ProductTrend inIls = calculator.compute(List.of(electra, amazon), TODAY.minusDays(1), NOW, ILS, rates, TTL);
        ProductTrend inUsd = calculator.compute(List.of(electra, amazon), TODAY.minusDays(1), NOW, USD, rates, TTL);

        assertThat(inIls.points())
                .extracting(p -> p.bestOffer().trackedItemId())
                .isEqualTo(inUsd.points().stream()
                        .map(p -> p.bestOffer().trackedItemId())
                        .toList());
        assertThat(lastPoint(inUsd).price())
                .isNotEqualByComparingTo(lastPoint(inIls).price());
    }

    @Test
    void aLargeFxMarginCanFlipTheWinnerBetweenDisplayCurrencies() {
        // Pins the documented caveat rather than a desirable behaviour: the margin models a foreign-
        // transaction fee, so it is skipped for a listing already priced in the display currency. It
        // therefore lands on different listings in different currencies, and a big enough margin can
        // change who wins. Invariance holds only at the default zero margin.
        PriceTrendCalculator withMargin = new PriceTrendCalculator(converter("25"));
        // 100 USD ≈ 363.64 ILS before margin — just under the 370 ILS listing.
        ListingWindow electra = listing(1, "Electra", rec(daysAgo(1), "370", ILS));
        ListingWindow amazon = listing(2, "Amazon", rec(daysAgo(1), "100", USD));
        HistoricalRateWindow rates = ratesOn(TODAY, TODAY.minusDays(1));
        when(rateService.currentSnapshot()).thenReturn(Optional.of(new RateSnapshot(TODAY, RATES)));

        ProductTrend inIls = withMargin.compute(List.of(electra, amazon), TODAY, NOW, ILS, rates, TTL);
        ProductTrend inUsd = withMargin.compute(List.of(electra, amazon), TODAY, NOW, USD, rates, TTL);

        // In ILS the USD listing carries the margin (363.64 × 1.25 = 454.55) and loses to 370.
        assertThat(lastPoint(inIls).bestOffer().shopName()).isEqualTo("Electra");
        // In USD the ILS listing carries it instead (101.75 × 1.25 ≈ 127.19) and loses to 100.
        assertThat(lastPoint(inUsd).bestOffer().shopName()).isEqualTo("Amazon");
    }

    @Test
    void completedDayIsValuedAtThatDaysRateNotTheFollowingDays() {
        // USD strengthens sharply overnight; the older day must keep the older rate.
        Map<String, Map<LocalDate, BigDecimal>> rates = new HashMap<>();
        rates.put(USD, Map.of(TODAY.minusDays(2), new BigDecimal("2.00"), TODAY.minusDays(1), new BigDecimal("1.00")));
        rates.put(ILS, Map.of(TODAY.minusDays(2), new BigDecimal("4.00"), TODAY.minusDays(1), new BigDecimal("4.00")));
        ListingWindow amazon = listing(1, "Amazon", rec(daysAgo(2), "100", USD));

        ProductTrend trend =
                calculator.compute(List.of(amazon), TODAY.minusDays(2), NOW, ILS, HistoricalRateWindow.of(rates), TTL);

        // Day −2: 100 * 4.00/2.00 = 200. Day −1: 100 * 4.00/1.00 = 400.
        assertThat(trend.points().get(0).price()).isEqualByComparingTo("200.0000");
        assertThat(trend.points().get(1).price()).isEqualByComparingTo("400.0000");
    }

    @Test
    void fxGapDay_usesTheNearestEarlierRate() {
        // Rates published only 4 days ago; the days after it still convert.
        HistoricalRateWindow rates = ratesOn(TODAY.minusDays(4));
        ListingWindow amazon = listing(1, "Amazon", rec(daysAgo(3), "100", USD));

        ProductTrend trend =
                calculator.compute(List.of(amazon), TODAY.minusDays(3), NOW.minus(1, ChronoUnit.DAYS), ILS, rates, TTL);

        assertThat(trend.points()).isNotEmpty();
        assertThat(trend.points().get(0).price()).isEqualByComparingTo("363.6364");
    }

    @Test
    void dayWithNoRateAtAll_emitsNoPoint() {
        // Rates start two days ago; the earlier day has nothing to convert with.
        HistoricalRateWindow rates = ratesOn(TODAY.minusDays(2));
        ListingWindow amazon = listing(1, "Amazon", rec(daysAgo(4), "100", USD));

        ProductTrend trend =
                calculator.compute(List.of(amazon), TODAY.minusDays(4), NOW.minus(1, ChronoUnit.DAYS), ILS, rates, TTL);

        assertThat(trend.points()).extracting(TrendPoint::t).doesNotContain(midnight(4), midnight(3));
        assertThat(trend.points()).extracting(TrendPoint::t).contains(midnight(2));
    }

    @Test
    void perQuoteFloor_aDateMissingOneCurrencyDoesNotShadowItsOlderRate() {
        // The most recent date carries USD only; ILS must fall back to its own earlier rate rather
        // than becoming unconvertible.
        Map<String, Map<LocalDate, BigDecimal>> rates = new HashMap<>();
        rates.put(USD, Map.of(TODAY.minusDays(3), new BigDecimal("1.10"), TODAY.minusDays(1), new BigDecimal("1.10")));
        rates.put(ILS, Map.of(TODAY.minusDays(3), new BigDecimal("4.00")));
        ListingWindow amazon = listing(1, "Amazon", rec(daysAgo(2), "100", USD));

        ProductTrend trend = calculator.compute(
                List.of(amazon),
                TODAY.minusDays(2),
                NOW.minus(1, ChronoUnit.DAYS),
                ILS,
                HistoricalRateWindow.of(rates),
                TTL);

        assertThat(trend.points()).isNotEmpty();
        assertThat(trend.points().get(0).price()).isEqualByComparingTo("363.6364");
    }

    @Test
    void marginIsAppliedOnTheHistoricalPathToo() {
        PriceTrendCalculator withMargin = new PriceTrendCalculator(converter("2.5"));
        ListingWindow amazon = listing(1, "Amazon", rec(daysAgo(2), "100", USD));

        ProductTrend trend = withMargin.compute(
                List.of(amazon),
                TODAY.minusDays(2),
                NOW.minus(1, ChronoUnit.DAYS),
                ILS,
                ratesOn(TODAY.minusDays(2)),
                TTL);

        assertThat(trend.points().get(0).price()).isEqualByComparingTo("372.7273");
    }

    // --- Staleness semantics (round-7) ---

    @Test
    void staleRateOnTheLatestPoint_setsConversionStale() {
        snapshotOn(TODAY.minusDays(10));
        ListingWindow amazon = listing(1, "Amazon", rec(daysAgo(1), "100", USD));

        ProductTrend trend = compute(List.of(amazon), 2, ratesOn(TODAY.minusDays(10)));

        assertThat(trend.conversionStale()).isTrue();
        assertThat(trend.conversionAsOf()).isEqualTo(TODAY.minusDays(10));
    }

    @Test
    void staleCurrentOrBaselineConversion_nullsTheDeltaRatherThanReportingIt() {
        snapshotOn(TODAY.minusDays(10));
        ListingWindow amazon = listing(1, "Amazon", rec(daysAgo(8), "120", USD), rec(daysAgo(1), "100", USD));

        ProductTrend trend = compute(List.of(amazon), 12, ratesOn(TODAY.minusDays(10)));

        assertThat(trend.delta7d()).isNull();
    }

    @Test
    void staleRateOnAnOlderPointOnly_leavesTheResponseFreshAndTheDeltaIntact() {
        // Old rate for the far past, fresh rates from a week ago onwards.
        snapshotOn(TODAY);
        Map<String, Map<LocalDate, BigDecimal>> rates = new HashMap<>();
        rates.put(USD, new HashMap<>(Map.of(TODAY.minusDays(30), new BigDecimal("1.10"))));
        rates.put(ILS, new HashMap<>(Map.of(TODAY.minusDays(30), new BigDecimal("4.00"))));
        for (int d = 9; d >= 0; d--) {
            rates.get(USD).put(TODAY.minusDays(d), new BigDecimal("1.10"));
            rates.get(ILS).put(TODAY.minusDays(d), new BigDecimal("4.00"));
        }
        ListingWindow amazon = listing(
                1, "Amazon", rec(daysAgo(20), "150", USD), rec(daysAgo(8), "120", USD), rec(daysAgo(1), "100", USD));

        ProductTrend trend =
                calculator.compute(List.of(amazon), TODAY.minusDays(25), NOW, ILS, HistoricalRateWindow.of(rates), TTL);

        assertThat(trend.conversionStale()).isFalse();
        assertThat(trend.delta7d()).isEqualByComparingTo("-16.67");
    }

    @Test
    void todaysPointUsesTheLiveSnapshotEvenWhenHistoricalFloorsDiffer() {
        // Snapshot says 1.10 USD/EUR; the historical window only knows a much older, different rate.
        snapshotOn(TODAY);
        Map<String, Map<LocalDate, BigDecimal>> divergent = new HashMap<>();
        divergent.put(USD, Map.of(TODAY.minusDays(1), new BigDecimal("2.20")));
        divergent.put(ILS, Map.of(TODAY.minusDays(1), new BigDecimal("4.00")));
        ListingWindow amazon = listing(1, "Amazon", rec(daysAgo(1), "100", USD));

        ProductTrend trend = calculator.compute(
                List.of(amazon), TODAY.minusDays(1), NOW, ILS, HistoricalRateWindow.of(divergent), TTL);

        assertThat(trend.points().get(0).price()).isEqualByComparingTo("181.8182"); // historical: 4.00/2.20
        assertThat(lastPoint(trend).price()).isEqualByComparingTo("363.6364"); // today: snapshot 4.00/1.10
    }

    // --- Boundaries and determinism ---

    @Test
    void recordStampedExactlyAtMidnight_belongsToTheNewDayOnly() {
        Instant midnight = TODAY.minusDays(2).atStartOfDay(ZoneOffset.UTC).toInstant();
        ListingWindow ksp =
                listing(1, "KSP", rec(midnight.minus(1, ChronoUnit.HOURS), "500", ILS), rec(midnight, "400", ILS));

        ProductTrend trend = compute(List.of(ksp), 4, HistoricalRateWindow.empty());

        // Day −3 ends at that midnight (exclusive), so it keeps 500; day −2 onwards sees 400.
        assertThat(trend.points().get(0).t()).isEqualTo(midnight(3));
        assertThat(trend.points().get(0).price()).isEqualByComparingTo("500");
        assertThat(trend.points().get(1).price()).isEqualByComparingTo("400");
    }

    @Test
    void equalConvertedPrices_areBrokenByTrackedItemId() {
        ListingWindow higherId = listing(9, "Bug", rec(daysAgo(1), "300", ILS));
        ListingWindow lowerId = listing(2, "KSP", rec(daysAgo(1), "300", ILS));

        ProductTrend trend = compute(List.of(higherId, lowerId), 2, HistoricalRateWindow.empty());

        assertThat(lastPoint(trend).bestOffer().trackedItemId()).isEqualTo(2);
    }

    // --- bestOffer provenance ---

    @Test
    void bestOffer_namesTheWinningListingAndWhenItWasObserved() {
        Instant observed = daysAgo(3);
        ListingWindow ksp = listing(7, "KSP", rec(observed, "199", ILS));

        ProductTrend trend = compute(List.of(ksp), 4, HistoricalRateWindow.empty());

        BestOffer offer = lastPoint(trend).bestOffer();
        assertThat(offer.trackedItemId()).isEqualTo(7);
        assertThat(offer.shopName()).isEqualTo("KSP");
        // Carried forward: today's point is attributed to an observation from three days ago.
        assertThat(offer.observedAt()).isEqualTo(observed);
    }

    @Test
    void bestOffer_followsTheWinnerWhenItMovesBetweenShops() {
        ListingWindow ksp = listing(1, "KSP", rec(daysAgo(3), "300", ILS));
        ListingWindow ivory = listing(2, "Ivory", rec(daysAgo(3), "350", ILS), rec(daysAgo(1), "250", ILS));

        ProductTrend trend = compute(List.of(ksp, ivory), 4, HistoricalRateWindow.empty());

        assertThat(trend.points())
                .extracting(p -> p.bestOffer().shopName())
                .containsExactly("KSP", "KSP", "Ivory", "Ivory");
    }

    // --- helpers ---

    // --- single-day window: how the dashboard calls this (#146) ---

    @Test
    void singleDayWindow_matchesTheFullWindowsCurrentPointAndDelta() {
        // The dashboard asks for windowStartDay = today over a two-record-per-listing input, and must
        // land on the same headline and delta a full-window call produces from the whole history.
        ListingWindow ksp =
                listing(1, "KSP", rec(daysAgo(8), "100", ILS), rec(daysAgo(3), "88", ILS), rec(daysAgo(1), "80", ILS));
        ListingWindow bug = listing(2, "Bug", rec(daysAgo(8), "120", ILS), rec(daysAgo(1), "95", ILS));
        // What the two-cutoff query would return: the latest at each cutoff, nothing in between.
        ListingWindow leanKsp = listing(1, "KSP", rec(daysAgo(8), "100", ILS), rec(daysAgo(1), "80", ILS));
        ListingWindow leanBug = listing(2, "Bug", rec(daysAgo(8), "120", ILS), rec(daysAgo(1), "95", ILS));

        ProductTrend full = compute(List.of(ksp, bug), 10, HistoricalRateWindow.empty());
        ProductTrend lean = computeToday(List.of(leanKsp, leanBug));

        assertThat(lean.points()).singleElement().satisfies(point -> {
            assertThat(point.t()).isEqualTo(midnight(0));
            assertThat(point.price()).isEqualByComparingTo(lastPoint(full).price());
            assertThat(point.bestOffer().trackedItemId())
                    .isEqualTo(lastPoint(full).bestOffer().trackedItemId());
            assertThat(point.bestOffer().shopName()).isEqualTo("KSP");
        });
        assertThat(lean.delta7d()).isEqualByComparingTo("-20.00").isEqualByComparingTo(full.delta7d());
        assertThat(lean.conversionAsOf()).isEqualTo(full.conversionAsOf());
        assertThat(lean.conversionStale()).isEqualTo(full.conversionStale());
    }

    @Test
    void singleDayWindow_withoutABaseline_stillEmitsTodaysPoint() {
        // "Less than a week of history" — the row shows a price with a "New" tag, not nothing.
        ProductTrend trend = computeToday(List.of(listing(1, "KSP", rec(daysAgo(2), "90", ILS))));

        assertThat(trend.points()).singleElement().satisfies(point -> assertThat(point.price())
                .isEqualByComparingTo("90"));
        assertThat(trend.delta7d()).isNull();
    }

    @Test
    void singleDayWindow_withNoEligibleCurrentOffer_emitsNothing() {
        // Latest observation is past the TTL, so nothing carries forward to today.
        ProductTrend trend = computeToday(List.of(listing(1, "KSP", rec(daysAgo(9), "100", ILS))));

        assertThat(trend.points()).isEmpty();
        assertThat(trend.delta7d()).isNull();
        assertThat(trend.conversionAsOf()).isNull();
        assertThat(trend.conversionStale()).isFalse();
    }

    @Test
    void singleDayWindow_crossCurrency_carriesTheWinningConversionMetadata() {
        snapshotOn(TODAY);
        ListingWindow amazon = listing(1, "Amazon", rec(daysAgo(1), "100", USD));

        ProductTrend trend = calculator.compute(List.of(amazon), TODAY, NOW, ILS, ratesOn(TODAY.minusDays(7)), TTL);

        // 100 USD -> ILS at 4.00/1.10.
        assertThat(lastPoint(trend).price()).isEqualByComparingTo("363.6364");
        assertThat(trend.conversionAsOf()).isEqualTo(TODAY);
        assertThat(trend.conversionStale()).isFalse();
    }

    /** The dashboard's call shape: today-only window, empty historical rates. */
    private ProductTrend computeToday(List<ListingWindow> listings) {
        return calculator.compute(listings, TODAY, NOW, ILS, HistoricalRateWindow.empty(), TTL);
    }

    private ProductTrend compute(List<ListingWindow> listings, int windowDays, HistoricalRateWindow rates) {
        return calculator.compute(listings, TODAY.minusDays(windowDays - 1L), NOW, ILS, rates, TTL);
    }

    private static TrendPoint lastPoint(ProductTrend trend) {
        return trend.points().get(trend.points().size() - 1);
    }

    private PriceConverter converter(String marginPercent) {
        CurrencyProperties props = new CurrencyProperties(
                ILS,
                new BigDecimal(marginPercent),
                new CurrencyProperties.Fx("", "", "0 30 16 * * *", Duration.ofSeconds(5), Duration.ofSeconds(10)));
        return new PriceConverter(rateService, props, FIXED_CLOCK);
    }

    private void snapshotOn(LocalDate asOf) {
        when(rateService.currentSnapshot()).thenReturn(Optional.of(new RateSnapshot(asOf, RATES)));
    }

    private static HistoricalRateWindow ratesOn(LocalDate... dates) {
        Map<String, Map<LocalDate, BigDecimal>> rates = new HashMap<>();
        Map<LocalDate, BigDecimal> usd = new HashMap<>();
        Map<LocalDate, BigDecimal> ils = new HashMap<>();
        for (LocalDate date : dates) {
            usd.put(date, new BigDecimal("1.10"));
            ils.put(date, new BigDecimal("4.00"));
        }
        rates.put(USD, usd);
        rates.put(ILS, ils);
        return HistoricalRateWindow.of(rates);
    }

    private static Instant daysAgo(int days) {
        return NOW.minus(days, ChronoUnit.DAYS);
    }

    private static Instant midnight(int daysAgo) {
        return TODAY.minusDays(daysAgo).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    /** Converted values carry the converter's output scale, which BigDecimal.equals() honours. */
    private static BigDecimal price(String value) {
        return new BigDecimal(value).setScale(4, RoundingMode.HALF_UP);
    }

    private static TrendRecordView rec(Instant at, String price, String currency) {
        return rec(at, price, currency, AvailabilityStatus.AVAILABLE);
    }

    private static TrendRecordView rec(Instant at, String price, String currency, AvailabilityStatus availability) {
        return new TrendRecordView(null, new BigDecimal(price), currency, availability, at);
    }

    private static ListingWindow listing(long id, String shop, TrendRecordView... records) {
        List<TrendRecordView> withId = new ArrayList<>();
        for (TrendRecordView r : records) {
            withId.add(new TrendRecordView(id, r.price(), r.currency(), r.availability(), r.timestamp()));
        }
        return new ListingWindow(id, shop, List.copyOf(withId));
    }
}
