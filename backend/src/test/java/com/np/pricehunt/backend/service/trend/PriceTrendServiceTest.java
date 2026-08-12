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
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.PriceTrendResponse;
import com.np.pricehunt.backend.dto.TrendRecordView;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

/** Loading, batching, clamping and DTO mapping — the calculator's own maths is covered separately. */
@ExtendWith(MockitoExtension.class)
class PriceTrendServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-20T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 20);
    private static final String ILS = "ILS";

    @Mock
    private ProductRepository productRepository;

    @Mock
    private TrackedItemRepository trackedItemRepository;

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
                productRepository,
                trackedItemRepository,
                priceRecordRepository,
                rateWindowLoader,
                calculator,
                new PriceTrendProperties(30, 730, 7),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void getProductTrend_unknownProduct_is404() {
        when(productRepository.findById(42L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProductTrend(42L, null, ILS))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Product not found");

        verifyNoInteractions(priceRecordRepository, rateWindowLoader, calculator);
    }

    @Test
    void getProductTrend_mapsCalculatorOutputOntoTheResponse() {
        Product product = product(1L);
        TrackedItem item = item(10L, "KSP", product);
        stubLoad(product, List.of(item));
        Instant observed = NOW.minus(2, ChronoUnit.DAYS);
        when(calculator.compute(any(), any(), any(), anyString(), any(), anyInt()))
                .thenReturn(new ProductTrend(
                        List.of(new TrendPoint(
                                midnight(1), new BigDecimal("199.0000"), new BestOffer(10L, "KSP", observed))),
                        new BigDecimal("-5.25"),
                        TODAY,
                        false));

        PriceTrendResponse response = service.getProductTrend(1L, null, ILS);

        assertThat(response.productId()).isEqualTo(1L);
        assertThat(response.displayCurrency()).isEqualTo(ILS);
        assertThat(response.delta7d()).isEqualByComparingTo("-5.25");
        assertThat(response.conversionAsOf()).isEqualTo(TODAY);
        assertThat(response.conversionStale()).isFalse();
        assertThat(response.sparkline()).hasSize(1);
        assertThat(response.sparkline().get(0).bestOffer().trackedItemId()).isEqualTo(10L);
        assertThat(response.sparkline().get(0).bestOffer().shopName()).isEqualTo("KSP");
        assertThat(response.sparkline().get(0).bestOffer().observedAt()).isEqualTo(observed);
    }

    @Test
    void getProductTrend_defaultWindowIsThirtyDays() {
        stubSingleProductWithOneItem();

        service.getProductTrend(1L, null, ILS);

        assertThat(capturedWindowStart()).isEqualTo(TODAY.minusDays(29));
    }

    @Test
    void getProductTrend_windowAboveTheMaximumIsClamped() {
        stubSingleProductWithOneItem();

        service.getProductTrend(1L, 9999, ILS);

        assertThat(capturedWindowStart()).isEqualTo(TODAY.minusDays(729));
    }

    @Test
    void getProductTrend_nonPositiveWindowIs400() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product(1L)));
        when(trackedItemRepository.findByProduct(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.getProductTrend(1L, 0, ILS))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("days must be >= 1");
    }

    @Test
    void fetchWindowAlwaysReachesTheDeltaBaseline_evenForAShortSparkline() {
        stubSingleProductWithOneItem();

        service.getProductTrend(1L, 7, ILS);

        ArgumentCaptor<Instant> from = ArgumentCaptor.forClass(Instant.class);
        verify(priceRecordRepository).findTrendRecords(any(), from.capture(), eq(NOW));
        // now−7d baseline minus the 7-day carry-forward TTL: records that far back can still be
        // the baseline's carried-forward observation.
        assertThat(from.getValue()).isEqualTo(NOW.minus(14, ChronoUnit.DAYS));
    }

    @Test
    void multipleProducts_shareOneRecordQueryAndOneRateLoad_withoutLeakingAcrossProducts() {
        Product first = product(1L);
        Product second = product(2L);
        TrackedItem kspA = item(10L, "KSP", first);
        TrackedItem amazonB = item(20L, "Amazon", second);

        when(priceRecordRepository.findTrendRecords(any(), any(), any()))
                .thenReturn(List.of(
                        trendRecord(10L, "100", ILS, NOW.minus(2, ChronoUnit.DAYS)),
                        trendRecord(20L, "50", "USD", NOW.minus(1, ChronoUnit.DAYS))));
        when(rateWindowLoader.load(any(), any(), any())).thenReturn(HistoricalRateWindow.empty());
        when(calculator.compute(any(), any(), any(), anyString(), any(), anyInt()))
                .thenReturn(ProductTrend.empty());

        // Product 3 has no listings at all — it must still get an entry, without extra queries.
        service.computeProductTrends(Map.of(1L, List.of(kspA), 2L, List.of(amazonB), 3L, List.of()), 30, ILS);

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
    void rateWindowIsLoadedForEveryCurrencySeenPlusTheDisplayCurrency() {
        stubSingleProductWithOneItem();
        when(priceRecordRepository.findTrendRecords(any(), any(), any()))
                .thenReturn(List.of(trendRecord(10L, "50", "USD", NOW.minus(1, ChronoUnit.DAYS))));

        service.getProductTrend(1L, 30, ILS);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> quotes = ArgumentCaptor.forClass(Set.class);
        verify(rateWindowLoader).load(eq(TODAY.minusDays(29)), eq(TODAY), quotes.capture());
        assertThat(quotes.getValue()).containsExactlyInAnyOrder("USD", ILS);
    }

    @Test
    void singleCurrencyProduct_asksForNoRatesSoTheLoaderSkipsTheDatabase() {
        stubSingleProductWithOneItem();
        when(priceRecordRepository.findTrendRecords(any(), any(), any()))
                .thenReturn(List.of(trendRecord(10L, "50", ILS, NOW.minus(1, ChronoUnit.DAYS))));

        service.getProductTrend(1L, 30, ILS);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Set<String>> quotes = ArgumentCaptor.forClass(Set.class);
        verify(rateWindowLoader).load(any(), any(), quotes.capture());
        // Everything is already in the display currency: those conversions never consult a rate.
        assertThat(quotes.getValue()).isEmpty();
    }

    @Test
    void productWithoutListings_skipsTheRecordQueryEntirely() {
        Product product = product(1L);
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findByProduct(product)).thenReturn(List.of());
        when(rateWindowLoader.load(any(), any(), any())).thenReturn(HistoricalRateWindow.empty());
        when(calculator.compute(any(), any(), any(), anyString(), any(), anyInt()))
                .thenReturn(ProductTrend.empty());

        PriceTrendResponse response = service.getProductTrend(1L, null, ILS);

        assertThat(response.sparkline()).isEmpty();
        assertThat(response.delta7d()).isNull();
        verifyNoInteractions(priceRecordRepository);
    }

    // --- helpers ---

    private void stubSingleProductWithOneItem() {
        Product product = product(1L);
        stubLoad(product, List.of(item(10L, "KSP", product)));
        when(calculator.compute(any(), any(), any(), anyString(), any(), anyInt()))
                .thenReturn(ProductTrend.empty());
    }

    private void stubLoad(Product product, List<TrackedItem> items) {
        when(productRepository.findById(product.getId())).thenReturn(Optional.of(product));
        when(trackedItemRepository.findByProduct(product)).thenReturn(items);
        when(priceRecordRepository.findTrendRecords(any(), any(), any())).thenReturn(List.of());
        when(rateWindowLoader.load(any(), any(), any())).thenReturn(HistoricalRateWindow.empty());
    }

    private LocalDate capturedWindowStart() {
        ArgumentCaptor<LocalDate> windowStart = ArgumentCaptor.forClass(LocalDate.class);
        verify(calculator).compute(any(), windowStart.capture(), any(), anyString(), any(), anyInt());
        return windowStart.getValue();
    }

    private static Instant midnight(int daysAgo) {
        return TODAY.minusDays(daysAgo).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    private static Product product(Long id) {
        return Product.builder().id(id).name("Product " + id).build();
    }

    private static TrackedItem item(Long id, String shop, Product product) {
        return TrackedItem.builder()
                .id(id)
                .url("https://" + shop.toLowerCase(java.util.Locale.ROOT) + ".example/item/" + id)
                .shopName(shop)
                .product(product)
                .build();
    }

    private static TrendRecordView trendRecord(Long itemId, String price, String currency, Instant at) {
        return new TrendRecordView(itemId, new BigDecimal(price), currency, AvailabilityStatus.AVAILABLE, at);
    }
}
