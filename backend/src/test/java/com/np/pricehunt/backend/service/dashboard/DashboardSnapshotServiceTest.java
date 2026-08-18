package com.np.pricehunt.backend.service.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.config.PriceTrendProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.dto.AvailabilityRollupStatus;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.projection.CutoffObservationRow;
import com.np.pricehunt.backend.repository.projection.CutoffSide;
import com.np.pricehunt.backend.repository.projection.DashboardListingRef;
import com.np.pricehunt.backend.repository.projection.TrendRecordView;
import com.np.pricehunt.backend.service.fx.HistoricalRateWindow;
import com.np.pricehunt.backend.service.fx.HistoricalRateWindowLoader;
import com.np.pricehunt.backend.service.trend.BestOffer;
import com.np.pricehunt.backend.service.trend.ListingWindow;
import com.np.pricehunt.backend.service.trend.PriceTrendCalculator;
import com.np.pricehunt.backend.service.trend.ProductTrend;
import com.np.pricehunt.backend.service.trend.TrendPoint;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The lean whole-set pass in isolation: window assembly, availability rollup, currency flag and
 * provenance. The delta arithmetic itself is the calculator's, and is stubbed here — {@code
 * PriceTrendCalculatorTest} owns it, and {@code DashboardQueryIntegrationTest} pins that the two
 * agree end to end.
 */
@ExtendWith(MockitoExtension.class)
class DashboardSnapshotServiceTest {

    private static final Instant AS_OF = Instant.parse("2026-03-20T12:00:00Z");
    private static final int TTL = 7;
    private static final String ILS = "ILS";
    private static final String USD = "USD";

    @Mock
    private PriceRecordRepository priceRecordRepository;

    @Mock
    private HistoricalRateWindowLoader rateWindowLoader;

    @Mock
    private PriceTrendCalculator calculator;

    private DashboardSnapshotService service;

    @BeforeEach
    void setUp() {
        service = new DashboardSnapshotService(
                priceRecordRepository, rateWindowLoader, calculator, new PriceTrendProperties(30, 730, TTL));
    }

    // --- window assembly ---

    @Test
    void windowsAreSortedOldestFirst_evenWhenTheQueryReturnsCurrentFirst() {
        // ListingWindow's contract is ascending, and the calculator scans from the tail for "latest at
        // or before". Native query result order is unspecified, so a current-first result must not be
        // passed through as-is or the baseline would be read as the current price.
        stubRows(
                row(1L, 101L, CutoffSide.CURRENT, "80", ILS, daysBefore(1)),
                row(1L, 100L, CutoffSide.BASELINE, "100", ILS, daysBefore(8)));
        stubEmptyRatesAndDelta();

        service.snapshotAll(Map.of(7L, List.of(listing(1L, "KSP"))), AS_OF, ILS);

        assertThat(capturedWindows())
                .singleElement()
                .extracting(ListingWindow::records)
                .satisfies(records -> assertThat(records)
                        .extracting(TrendRecordView::timestamp)
                        .containsExactly(daysBefore(8), daysBefore(1)));
    }

    @Test
    void oneRecordSelectedByBothSides_reachesTheCalculatorOnce() {
        // With a 7-day TTL the windows share a boundary, so the same record can win both sides. It is
        // one observation, not two, and feeding it twice would make the calculator compare it to itself.
        stubRows(
                row(1L, 55L, CutoffSide.CURRENT, "250", ILS, daysBefore(7)),
                row(1L, 55L, CutoffSide.BASELINE, "250", ILS, daysBefore(7)));
        stubEmptyRatesAndDelta();

        service.snapshotAll(Map.of(7L, List.of(listing(1L, "KSP"))), AS_OF, ILS);

        assertThat(capturedWindows()).singleElement().satisfies(window -> assertThat(window.records())
                .hasSize(1));
    }

    @Test
    void oneRecordSelectedByBothSides_stillDrivesAvailabilityAndProvenance() {
        // The dedupe must never cost the row its CURRENT tag: availability, mixedCurrencies and the
        // as-listed price all read the current-side row.
        stubRows(
                row(1L, 55L, CutoffSide.BASELINE, "250", USD, daysBefore(7)),
                row(1L, 55L, CutoffSide.CURRENT, "250", USD, daysBefore(7), AvailabilityStatus.AVAILABLE));
        stubRates();
        stubTrend(headline("1000", 1L, "KSP", null));

        ProductDashboardSnapshot snapshot = snapshotOne(Map.of(7L, List.of(listing(1L, "KSP"))));

        assertThat(snapshot.availability().status()).isEqualTo(AvailabilityRollupStatus.AVAILABLE);
        assertThat(snapshot.bestPriceOriginal()).isEqualByComparingTo("250");
        assertThat(snapshot.bestPriceOriginalCurrency()).isEqualTo(USD);
    }

    @Test
    void listingWithNoObservationGetsAnEmptyWindow_notADroppedListing() {
        stubRows();
        stubEmptyRatesAndDelta();

        service.snapshotAll(Map.of(7L, List.of(listing(1L, "KSP"), listing(2L, "Bug"))), AS_OF, ILS);

        assertThat(capturedWindows()).hasSize(2).allSatisfy(window -> assertThat(window.records())
                .isEmpty());
    }

    @Test
    void queryWindowsAreDerivedFromTheTtlAndTheSevenDayBaseline() {
        stubRows();
        stubEmptyRatesAndDelta();

        service.snapshotAll(Map.of(7L, List.of(listing(1L, "KSP"))), AS_OF, ILS);

        verify(priceRecordRepository)
                .findCutoffObservations(
                        eq(AS_OF.minus(TTL, ChronoUnit.DAYS)),
                        eq(AS_OF),
                        eq(AS_OF.minus(7L + TTL, ChronoUnit.DAYS)),
                        eq(AS_OF.minus(7, ChronoUnit.DAYS)));
    }

    // --- rate loading ---

    @Test
    void singleCurrencyCatalogue_asksForNoRatesSoTheLoaderSkipsTheDatabase() {
        stubRows(row(1L, 10L, CutoffSide.CURRENT, "50", ILS, daysBefore(1)));
        stubRates();
        stubTrend(ProductTrend.empty());

        service.snapshotAll(Map.of(7L, List.of(listing(1L, "KSP"))), AS_OF, ILS);

        assertThat(capturedQuotes()).isEmpty();
    }

    @Test
    void crossCurrencyCatalogue_loadsBothLegsOnTheBaselineDayOnly() {
        stubRows(row(1L, 10L, CutoffSide.CURRENT, "50", USD, daysBefore(1)));
        stubRates();
        stubTrend(ProductTrend.empty());

        service.snapshotAll(Map.of(7L, List.of(listing(1L, "KSP"))), AS_OF, ILS);

        LocalDate baselineDay = LocalDate.ofInstant(AS_OF.minus(7, ChronoUnit.DAYS), ZoneOffset.UTC);
        verify(rateWindowLoader).load(eq(baselineDay), eq(baselineDay), any());
        assertThat(capturedQuotes()).containsExactlyInAnyOrder(USD, ILS);
    }

    @Test
    void ratesAreLoadedOncePerRequest_notOncePerProduct() {
        stubRows(
                row(1L, 10L, CutoffSide.CURRENT, "50", USD, daysBefore(1)),
                row(2L, 20L, CutoffSide.CURRENT, "60", USD, daysBefore(1)));
        stubRates();
        stubTrend(ProductTrend.empty());

        service.snapshotAll(Map.of(7L, List.of(listing(1L, "KSP")), 8L, List.of(listing(2L, "Bug"))), AS_OF, ILS);

        verify(rateWindowLoader).load(any(), any(), any());
        verify(priceRecordRepository).findCutoffObservations(any(), any(), any(), any());
    }

    // --- availability rollup ---

    @Test
    void allListingsAvailable_rollsUpToAvailable() {
        assertRollup(
                AvailabilityRollupStatus.AVAILABLE, 2, 2, AvailabilityStatus.AVAILABLE, AvailabilityStatus.AVAILABLE);
    }

    @Test
    void allListingsUnavailable_rollsUpToUnavailable() {
        assertRollup(
                AvailabilityRollupStatus.UNAVAILABLE,
                0,
                2,
                AvailabilityStatus.UNAVAILABLE,
                AvailabilityStatus.UNAVAILABLE);
    }

    @Test
    void someAvailableSomeNot_rollsUpToMixed() {
        assertRollup(
                AvailabilityRollupStatus.MIXED, 1, 2, AvailabilityStatus.AVAILABLE, AvailabilityStatus.UNAVAILABLE);
    }

    @Test
    void availablePlusUnknown_isStillMixed() {
        assertRollup(AvailabilityRollupStatus.MIXED, 1, 2, AvailabilityStatus.AVAILABLE, AvailabilityStatus.UNKNOWN);
    }

    @Test
    void unknownPlusUnavailable_rollsUpToUnknown() {
        // Never claim a product is unavailable while some shop's state is simply unknown.
        assertRollup(
                AvailabilityRollupStatus.UNKNOWN, 0, 2, AvailabilityStatus.UNKNOWN, AvailabilityStatus.UNAVAILABLE);
    }

    @Test
    void listingWithNoRecentObservation_countsAsUnknown_notAsAbsent() {
        // The TTL divergence from /api/products, deliberately: an observation older than the carry-
        // forward window is "not checked", so it neither counts as in stock nor leaves the denominator.
        stubRows(row(1L, 10L, CutoffSide.CURRENT, "50", ILS, daysBefore(1)));
        stubEmptyRatesAndDelta();

        ProductDashboardSnapshot snapshot = snapshotOne(Map.of(7L, List.of(listing(1L, "KSP"), listing(2L, "Stale"))));

        assertThat(snapshot.availability())
                .isEqualTo(new ProductDashboardSnapshot.AvailabilitySummary(AvailabilityRollupStatus.MIXED, 1, 2));
    }

    @Test
    void productWithNoListings_reportsNothingTracked() {
        stubRows();
        stubEmptyRatesAndDelta();

        ProductDashboardSnapshot snapshot = snapshotOne(Map.of(7L, List.of()));

        assertThat(snapshot.availability())
                .isEqualTo(new ProductDashboardSnapshot.AvailabilitySummary(AvailabilityRollupStatus.UNKNOWN, 0, 0));
        assertThat(snapshot.bestPriceConverted()).isNull();
        assertThat(snapshot.mixedCurrencies()).isFalse();
    }

    // --- currency flag + provenance ---

    @Test
    void listingsInDifferentCurrencies_setMixedCurrencies() {
        stubRows(
                row(1L, 10L, CutoffSide.CURRENT, "50", ILS, daysBefore(1)),
                row(2L, 20L, CutoffSide.CURRENT, "20", USD, daysBefore(1)));
        stubRates();
        stubTrend(ProductTrend.empty());

        ProductDashboardSnapshot snapshot = snapshotOne(Map.of(7L, List.of(listing(1L, "KSP"), listing(2L, "Amazon"))));

        assertThat(snapshot.mixedCurrencies()).isTrue();
    }

    @Test
    void unavailableListingStillCountsTowardsMixedCurrencies() {
        // The flag describes the catalogue, not what is buyable right now — mirroring the older row.
        stubRows(
                row(1L, 10L, CutoffSide.CURRENT, "50", ILS, daysBefore(1)),
                row(2L, 20L, CutoffSide.CURRENT, "20", USD, daysBefore(1), AvailabilityStatus.UNAVAILABLE));
        stubRates();
        stubTrend(ProductTrend.empty());

        ProductDashboardSnapshot snapshot = snapshotOne(Map.of(7L, List.of(listing(1L, "KSP"), listing(2L, "Amazon"))));

        assertThat(snapshot.mixedCurrencies()).isTrue();
    }

    @Test
    void headlineFieldsComeFromTheWinningListingsCurrentRow() {
        stubRows(
                row(1L, 10L, CutoffSide.CURRENT, "50", ILS, daysBefore(1)),
                row(2L, 20L, CutoffSide.CURRENT, "12", USD, daysBefore(1)));
        stubRates();
        stubTrend(new ProductTrend(
                List.of(new TrendPoint(
                        daysBefore(1), new BigDecimal("43.6364"), new BestOffer(2L, "Amazon", daysBefore(1)))),
                new BigDecimal("-8.00"),
                LocalDate.of(2026, 3, 19),
                true));

        ProductDashboardSnapshot snapshot = snapshotOne(Map.of(7L, List.of(listing(1L, "KSP"), listing(2L, "Amazon"))));

        assertThat(snapshot.productId()).isEqualTo(7L);
        assertThat(snapshot.bestPriceConverted()).isEqualByComparingTo("43.6364");
        assertThat(snapshot.bestPriceOriginal()).isEqualByComparingTo("12");
        assertThat(snapshot.bestPriceOriginalCurrency()).isEqualTo(USD);
        assertThat(snapshot.bestPriceShop()).isEqualTo("Amazon");
        assertThat(snapshot.bestTrackedItemId()).isEqualTo(2L);
        assertThat(snapshot.conversionAsOf()).isEqualTo(LocalDate.of(2026, 3, 19));
        assertThat(snapshot.conversionStale()).isTrue();
        assertThat(snapshot.delta7d()).isEqualByComparingTo("-8.00");
    }

    @Test
    void noEligibleOffer_nullsTheWholeBestPriceCluster() {
        stubRows(row(1L, 10L, CutoffSide.CURRENT, "50", ILS, daysBefore(1), AvailabilityStatus.UNAVAILABLE));
        stubRates();
        stubTrend(ProductTrend.empty());

        ProductDashboardSnapshot snapshot = snapshotOne(Map.of(7L, List.of(listing(1L, "KSP"))));

        assertThat(snapshot.bestPriceConverted()).isNull();
        assertThat(snapshot.bestPriceOriginal()).isNull();
        assertThat(snapshot.bestPriceOriginalCurrency()).isNull();
        assertThat(snapshot.bestPriceShop()).isNull();
        assertThat(snapshot.bestTrackedItemId()).isNull();
        assertThat(snapshot.conversionAsOf()).isNull();
        assertThat(snapshot.conversionStale()).isFalse();
        assertThat(snapshot.delta7d()).isNull();
    }

    @Test
    void everyRequestedProductGetsASnapshot_evenWithNoDataAtAll() {
        stubRows();
        stubEmptyRatesAndDelta();

        Map<Long, ProductDashboardSnapshot> snapshots =
                service.snapshotAll(Map.of(7L, List.of(), 8L, List.of()), AS_OF, ILS);

        assertThat(snapshots).containsOnlyKeys(7L, 8L);
    }

    // --- fixtures ---

    private void assertRollup(
            AvailabilityRollupStatus expected, int expectedAvailable, int expectedTotal, AvailabilityStatus... states) {
        List<CutoffObservationRow> rows = new ArrayList<>();
        List<DashboardListingRef> listings = new ArrayList<>();
        for (int i = 0; i < states.length; i++) {
            long itemId = i + 1L;
            listings.add(listing(itemId, "Shop" + itemId));
            rows.add(row(itemId, 100 + itemId, CutoffSide.CURRENT, "50", ILS, daysBefore(1), states[i]));
        }
        stubRows(rows.toArray(CutoffObservationRow[]::new));
        stubEmptyRatesAndDelta();

        ProductDashboardSnapshot snapshot = snapshotOne(Map.of(7L, listings));

        assertThat(snapshot.availability())
                .isEqualTo(
                        new ProductDashboardSnapshot.AvailabilitySummary(expected, expectedAvailable, expectedTotal));
    }

    private ProductDashboardSnapshot snapshotOne(Map<Long, List<DashboardListingRef>> listings) {
        return service.snapshotAll(listings, AS_OF, ILS)
                .get(listings.keySet().iterator().next());
    }

    private void stubRows(CutoffObservationRow... rows) {
        when(priceRecordRepository.findCutoffObservations(any(), any(), any(), any()))
                .thenReturn(List.of(rows));
    }

    private void stubRates() {
        when(rateWindowLoader.load(any(), any(), any())).thenReturn(HistoricalRateWindow.empty());
    }

    /** The calculator is called with a one-day window, so it returns at most one point. */
    private void stubTrend(ProductTrend trend) {
        when(calculator.compute(any(), any(), any(), anyString(), any(), anyInt()))
                .thenReturn(trend);
    }

    /** A one-point trend standing in for "this product has a current best offer". */
    private static ProductTrend headline(String price, long trackedItemId, String shop, BigDecimal delta7d) {
        return new ProductTrend(
                List.of(new TrendPoint(AS_OF, new BigDecimal(price), new BestOffer(trackedItemId, shop, AS_OF))),
                delta7d,
                null,
                false);
    }

    private void stubEmptyRatesAndDelta() {
        stubRates();
        stubTrend(ProductTrend.empty());
    }

    @SuppressWarnings("unchecked")
    private List<ListingWindow> capturedWindows() {
        ArgumentCaptor<List<ListingWindow>> windows = ArgumentCaptor.forClass(List.class);
        verify(calculator).compute(windows.capture(), any(), any(), anyString(), any(), anyInt());
        return windows.getValue();
    }

    @SuppressWarnings("unchecked")
    private Set<String> capturedQuotes() {
        ArgumentCaptor<Set<String>> quotes = ArgumentCaptor.forClass(Set.class);
        verify(rateWindowLoader).load(any(), any(), quotes.capture());
        return quotes.getValue();
    }

    private static Instant daysBefore(int days) {
        return AS_OF.minus(days, ChronoUnit.DAYS);
    }

    private static DashboardListingRef listing(long trackedItemId, String shopName) {
        return new DashboardListingRef(trackedItemId, 7L, shopName);
    }

    private static CutoffObservationRow row(
            long trackedItemId, long recordId, CutoffSide side, String price, String currency, Instant observedAt) {
        return row(trackedItemId, recordId, side, price, currency, observedAt, AvailabilityStatus.AVAILABLE);
    }

    private static CutoffObservationRow row(
            long trackedItemId,
            long recordId,
            CutoffSide side,
            String price,
            String currency,
            Instant observedAt,
            AvailabilityStatus availability) {
        // A hand-rolled stub rather than a Mockito mock: the service reads six getters on every row,
        // and stubbing each of them per fixture would bury the fixture in ceremony.
        return new CutoffObservationRow() {
            @Override
            public Long getTrackedItemId() {
                return trackedItemId;
            }

            @Override
            public Long getRecordId() {
                return recordId;
            }

            @Override
            public BigDecimal getPrice() {
                return new BigDecimal(price);
            }

            @Override
            public String getCurrency() {
                return currency;
            }

            @Override
            public AvailabilityStatus getAvailability() {
                return availability;
            }

            @Override
            public Instant getObservedAt() {
                return observedAt;
            }

            @Override
            public CutoffSide getSide() {
                return side;
            }
        };
    }
}
