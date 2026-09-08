package com.np.pricehunt.backend.service.trend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.config.PriceTrendProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.exception.ValidationException;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.projection.DashboardListingRef;
import com.np.pricehunt.backend.repository.projection.TrendRecordView;
import com.np.pricehunt.backend.service.fx.HistoricalRateWindow;
import com.np.pricehunt.backend.service.fx.HistoricalRateWindowLoader;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
 * The trend engine's loading and windowing (issue #145): one record query and one rate load per
 * batch, the fetch floor, and the {@code days} bounds. The engine takes listing refs the tenancy port
 * resolved (#246) and never sees a user; the endpoint that resolves the product and maps the response
 * is {@code TrackedProductQueryService}, tested there.
 */
@ExtendWith(MockitoExtension.class)
class PriceTrendServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-20T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 20);
    private static final String ILS = "ILS";

    @Mock
    private PriceRecordRepository priceRecordRepository;

    @Mock
    private HistoricalRateWindowLoader rateWindowLoader;

    @Mock
    private PriceTrendCalculator calculator;

    private PriceTrendService service;

    @BeforeEach
    void setUp() {
        // Construct here, not as a field initializer: @Mock fields are injected after field init.
        service = new PriceTrendService(
                priceRecordRepository,
                rateWindowLoader,
                calculator,
                new PriceTrendProperties(30, 730, 7),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void defaultWindowIsThirtyDays() {
        stubBatchOnly();

        service.computeProductTrends(oneProductWithOneListing(), null, ILS);

        assertThat(capturedWindowStart()).isEqualTo(TODAY.minusDays(29));
    }

    @Test
    void windowAboveTheMaximumIsClamped() {
        stubBatchOnly();

        service.computeProductTrends(oneProductWithOneListing(), 9999, ILS);

        assertThat(capturedWindowStart()).isEqualTo(TODAY.minusDays(729));
    }

    @Test
    void nonPositiveWindowIs400() {
        assertThatThrownBy(() -> service.computeProductTrends(oneProductWithOneListing(), 0, ILS))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("days must be >= 1");

        verifyNoInteractions(priceRecordRepository, rateWindowLoader, calculator);
    }

    @Test
    void fetchWindowAlwaysReachesTheDeltaBaseline_evenForAShortSparkline() {
        stubBatchOnly();

        service.computeProductTrends(oneProductWithOneListing(), 7, ILS);

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(priceRecordRepository).findTrendRecords(any(), from.capture(), eq(NOW));
        // now−7d baseline minus the 7-day carry-forward TTL: records that far back can still be
        // the baseline's carried-forward observation.
        assertThat(from.getValue()).isEqualTo(NOW.minus(14, ChronoUnit.DAYS));
    }

    @Test
    void multipleProducts_shareOneRecordQueryAndOneRateLoad_withoutLeakingAcrossProducts() {
        when(priceRecordRepository.findTrendRecords(any(), any(), any()))
                .thenReturn(List.of(
                        trendRecord(10L, "100", ILS, NOW.minus(2, ChronoUnit.DAYS)),
                        trendRecord(20L, "50", "USD", NOW.minus(1, ChronoUnit.DAYS))));
        when(rateWindowLoader.load(any(), any(), any())).thenReturn(HistoricalRateWindow.empty());
        when(calculator.compute(any(), any(), any(), anyString(), any(), anyInt()))
                .thenReturn(ProductTrend.empty());

        // Product 3 has no listings at all — it must still get an entry, without extra queries.
        service.computeProductTrends(
                Map.of(1L, List.of(listing(10L, 1L, "KSP")), 2L, List.of(listing(20L, 2L, "Amazon")), 3L, List.of()),
                30,
                ILS);

        verify(priceRecordRepository, times(1)).findTrendRecords(any(), any(), any());
        verify(rateWindowLoader, times(1)).load(any(), any(), any());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ListingWindow>> listings = ArgumentCaptor.forClass(List.class);
        verify(calculator, times(3)).compute(listings.capture(), any(), any(), anyString(), any(), anyInt());

        List<List<ListingWindow>> perProduct = listings.getAllValues();
        assertThat(perProduct).anySatisfy(windows -> {
            assertThat(windows).hasSize(1);
            assertThat(windows.get(0).trackedItemId()).isEqualTo(10L);
            assertThat(windows.get(0).records()).hasSize(1);
        });
        assertThat(perProduct).anySatisfy(windows -> {
            assertThat(windows).hasSize(1);
            assertThat(windows.get(0).trackedItemId()).isEqualTo(20L);
            assertThat(windows.get(0).records()).hasSize(1);
        });
        assertThat(perProduct).anySatisfy(windows -> assertThat(windows).isEmpty());
    }

    @Test
    void asOfOverload_evaluatesAtTheSuppliedInstant_notTheClock() {
        // The dashboard pins one instant across its lean pass and its sparkline pass; if this
        // overload silently re-read the clock, a request straddling UTC midnight could report a
        // headline price and a series that disagree about which day "today" is.
        stubBatchOnly();
        Instant pinned = NOW.minus(3, ChronoUnit.DAYS);

        service.computeProductTrendsAsOf(oneProductWithOneListing(), 30, ILS, pinned);

        verify(calculator).compute(any(), eq(TODAY.minusDays(32)), eq(pinned), anyString(), any(), anyInt());
    }

    @Test
    void batchWithoutAnAsOf_readsTheClock() {
        stubBatchOnly();

        service.computeProductTrends(oneProductWithOneListing(), 30, ILS);

        verify(calculator).compute(any(), eq(TODAY.minusDays(29)), eq(NOW), anyString(), any(), anyInt());
    }

    @Test
    void rateWindowIsLoadedForEveryCurrencySeenPlusTheDisplayCurrency() {
        when(priceRecordRepository.findTrendRecords(any(), any(), any()))
                .thenReturn(List.of(trendRecord(10L, "50", "USD", NOW.minus(1, ChronoUnit.DAYS))));
        when(rateWindowLoader.load(any(), any(), any())).thenReturn(HistoricalRateWindow.empty());
        when(calculator.compute(any(), any(), any(), anyString(), any(), anyInt()))
                .thenReturn(ProductTrend.empty());

        service.computeProductTrends(oneProductWithOneListing(), 30, ILS);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> quotes = ArgumentCaptor.forClass(Set.class);
        verify(rateWindowLoader).load(eq(TODAY.minusDays(29)), eq(TODAY), quotes.capture());
        assertThat(quotes.getValue()).containsExactlyInAnyOrder("USD", ILS);
    }

    @Test
    void singleCurrencyProduct_asksForNoRatesSoTheLoaderSkipsTheDatabase() {
        when(priceRecordRepository.findTrendRecords(any(), any(), any()))
                .thenReturn(List.of(trendRecord(10L, "50", ILS, NOW.minus(1, ChronoUnit.DAYS))));
        when(rateWindowLoader.load(any(), any(), any())).thenReturn(HistoricalRateWindow.empty());
        when(calculator.compute(any(), any(), any(), anyString(), any(), anyInt()))
                .thenReturn(ProductTrend.empty());

        service.computeProductTrends(oneProductWithOneListing(), 30, ILS);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> quotes = ArgumentCaptor.forClass(Set.class);
        verify(rateWindowLoader).load(any(), any(), quotes.capture());
        // Everything is already in the display currency: those conversions never consult a rate.
        assertThat(quotes.getValue()).isEmpty();
    }

    @Test
    void productWithoutListings_skipsTheRecordQueryEntirely() {
        when(rateWindowLoader.load(any(), any(), any())).thenReturn(HistoricalRateWindow.empty());
        when(calculator.compute(any(), any(), any(), anyString(), any(), anyInt()))
                .thenReturn(ProductTrend.empty());

        Map<Long, ProductTrend> trends = service.computeProductTrends(Map.of(1L, List.of()), null, ILS);

        assertThat(trends.get(1L).points()).isEmpty();
        assertThat(trends.get(1L).delta7d()).isNull();
        verifyNoInteractions(priceRecordRepository);
    }

    // --- helpers ---

    private void stubBatchOnly() {
        when(priceRecordRepository.findTrendRecords(any(), any(), any())).thenReturn(List.of());
        when(rateWindowLoader.load(any(), any(), any())).thenReturn(HistoricalRateWindow.empty());
        when(calculator.compute(any(), any(), any(), anyString(), any(), anyInt()))
                .thenReturn(ProductTrend.empty());
    }

    private static Map<Long, List<DashboardListingRef>> oneProductWithOneListing() {
        return Map.of(1L, List.of(listing(10L, 1L, "KSP")));
    }

    private LocalDate capturedWindowStart() {
        ArgumentCaptor<LocalDate> windowStart = ArgumentCaptor.forClass(LocalDate.class);
        verify(calculator).compute(any(), windowStart.capture(), any(), anyString(), any(), anyInt());
        return windowStart.getValue();
    }

    private static DashboardListingRef listing(Long id, Long productId, String shop) {
        return new DashboardListingRef(id, productId, shop);
    }

    private static TrendRecordView trendRecord(Long itemId, String price, String currency, Instant at) {
        return new TrendRecordView(itemId, new BigDecimal(price), currency, AvailabilityStatus.AVAILABLE, at);
    }
}
