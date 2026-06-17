package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.config.PriceHistoryProperties;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.PriceBasis;
import com.np.pricehunt.backend.dto.PriceHistoryResponse;
import com.np.pricehunt.backend.dto.ProductDetailResponse;
import com.np.pricehunt.backend.dto.ProductSummaryResponse;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.service.fx.ConvertedAmount;
import com.np.pricehunt.backend.service.fx.PriceConverter;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ProductQueryServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 24);

    @Mock
    private ProductRepository productRepository;

    @Mock
    private TrackedItemRepository trackedItemRepository;

    @Mock
    private PriceRecordRepository priceRecordRepository;

    @Mock
    private PriceConverter priceConverter;

    private ProductQueryService service;

    private Product product;
    private TrackedItem itemA;
    private TrackedItem itemB;

    @BeforeEach
    void setUp() {
        service = new ProductQueryService(
                productRepository,
                trackedItemRepository,
                priceRecordRepository,
                priceConverter,
                new PriceHistoryProperties(90));
        product = Product.builder().id(1L).name("Laptop").build();
        itemA = TrackedItem.builder()
                .id(1L)
                .url("https://amazon.com/dp/1")
                .shopName("amazon.com")
                .product(product)
                .build();
        itemB = TrackedItem.builder()
                .id(2L)
                .url("https://bestbuy.com/p/1")
                .shopName("bestbuy.com")
                .product(product)
                .build();
    }

    // --- getAllProducts ---

    @Test
    void getAllProducts_singleCurrency_picksLowestConverted() {
        PriceRecord cheapest = priceRecord(itemA, "49.99", "USD");
        PriceRecord pricier = priceRecord(itemB, "59.99", "USD");
        stubProductWithItems(List.of(itemA, itemB));
        stubLatestPrices(Map.of(itemA, cheapest, itemB, pricier));
        stubIdentityConversion("49.99", "USD");
        stubIdentityConversion("59.99", "USD");

        Page<ProductSummaryResponse> page = service.getAllProducts(PageRequest.of(0, 20), "USD");

        ProductSummaryResponse summary = page.getContent().get(0);
        assertThat(summary.bestPriceConverted()).isEqualByComparingTo("49.99");
        assertThat(summary.bestPriceConvertedCurrency()).isEqualTo("USD");
        assertThat(summary.bestPriceOriginal()).isEqualByComparingTo("49.99");
        assertThat(summary.bestPriceOriginalCurrency()).isEqualTo("USD");
        assertThat(summary.bestPriceShop()).isEqualTo("amazon.com");
        assertThat(summary.mixedCurrencies()).isFalse();
        assertThat(summary.priceBasis()).isEqualTo(PriceBasis.AS_LISTED);
        assertThat(summary.trackedStoreCount()).isEqualTo(2);
    }

    @Test
    void getAllProducts_mixedCurrencies_picksLowestAfterConversion() {
        PriceRecord usd = priceRecord(itemA, "100", "USD");
        PriceRecord ils = priceRecord(itemB, "500", "ILS");
        stubProductWithItems(List.of(itemA, itemB));
        stubLatestPrices(Map.of(itemA, usd, itemB, ils));
        when(priceConverter.convert(new BigDecimal("100"), "USD", "ILS"))
                .thenReturn(new ConvertedAmount(new BigDecimal("363.6364"), TODAY, false));
        when(priceConverter.convert(new BigDecimal("500"), "ILS", "ILS"))
                .thenReturn(new ConvertedAmount(new BigDecimal("500"), null, false));

        ProductSummaryResponse summary = service.getAllProducts(PageRequest.of(0, 20), "ILS")
                .getContent()
                .get(0);

        assertThat(summary.bestPriceConverted()).isEqualByComparingTo("363.6364");
        assertThat(summary.bestPriceConvertedCurrency()).isEqualTo("ILS");
        assertThat(summary.bestPriceOriginal()).isEqualByComparingTo("100");
        assertThat(summary.bestPriceOriginalCurrency()).isEqualTo("USD");
        assertThat(summary.bestPriceShop()).isEqualTo("amazon.com");
        assertThat(summary.mixedCurrencies()).isTrue();
        assertThat(summary.conversionAsOf()).isEqualTo(TODAY);
    }

    @Test
    void getAllProducts_winnerChosenByConvertedValueNotOriginalOrder() {
        // itemA priced higher in ILS originally; itemB priced lower in USD originally — after conversion to ILS,
        // itemA wins. The original currency must not bias the comparison.
        PriceRecord ilsItem = priceRecord(itemA, "300", "ILS");
        PriceRecord usdItem = priceRecord(itemB, "100", "USD");
        stubProductWithItems(List.of(itemA, itemB));
        stubLatestPrices(Map.of(itemA, ilsItem, itemB, usdItem));
        when(priceConverter.convert(new BigDecimal("300"), "ILS", "ILS"))
                .thenReturn(new ConvertedAmount(new BigDecimal("300"), null, false));
        when(priceConverter.convert(new BigDecimal("100"), "USD", "ILS"))
                .thenReturn(new ConvertedAmount(new BigDecimal("363.6364"), TODAY, false));

        ProductSummaryResponse summary = service.getAllProducts(PageRequest.of(0, 20), "ILS")
                .getContent()
                .get(0);

        assertThat(summary.bestPriceShop()).isEqualTo("amazon.com");
        assertThat(summary.bestPriceOriginalCurrency()).isEqualTo("ILS");
    }

    @Test
    void getAllProducts_noItemsHavePrices_emptyBestPrice() {
        stubProductWithItems(List.of(itemA, itemB));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(itemA))
                .thenReturn(Optional.empty());
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(itemB))
                .thenReturn(Optional.empty());

        ProductSummaryResponse summary = service.getAllProducts(PageRequest.of(0, 20), "ILS")
                .getContent()
                .get(0);

        assertThat(summary.bestPriceConverted()).isNull();
        assertThat(summary.bestPriceOriginal()).isNull();
        assertThat(summary.mixedCurrencies()).isFalse();
        assertThat(summary.priceBasis()).isEqualTo(PriceBasis.AS_LISTED);
    }

    @Test
    void getAllProducts_allConversionsFail_emptyBestPricePreservesMixedFlag() {
        PriceRecord usd = priceRecord(itemA, "100", "USD");
        PriceRecord eur = priceRecord(itemB, "85", "EUR");
        stubProductWithItems(List.of(itemA, itemB));
        stubLatestPrices(Map.of(itemA, usd, itemB, eur));
        when(priceConverter.convert(new BigDecimal("100"), "USD", "ZZZ")).thenReturn(null);
        when(priceConverter.convert(new BigDecimal("85"), "EUR", "ZZZ")).thenReturn(null);

        ProductSummaryResponse summary = service.getAllProducts(PageRequest.of(0, 20), "ZZZ")
                .getContent()
                .get(0);

        assertThat(summary.bestPriceConverted()).isNull();
        assertThat(summary.mixedCurrencies()).isTrue();
        assertThat(summary.anyAvailable()).isTrue();
        assertThat(summary.trackedStoreCount()).isEqualTo(2);
    }

    @Test
    void getAllProducts_anyAvailable_trueWhenAtLeastOneItemInStock() {
        PriceRecord unavailable = PriceRecord.builder()
                .price(new BigDecimal("50"))
                .currency("USD")
                .available(false)
                .extractionSource(ExtractionSource.STRUCTURED)
                .trackedItem(itemA)
                .build();
        PriceRecord available = PriceRecord.builder()
                .price(new BigDecimal("55"))
                .currency("USD")
                .available(true)
                .extractionSource(ExtractionSource.STRUCTURED)
                .trackedItem(itemB)
                .build();
        stubProductWithItems(List.of(itemA, itemB));
        stubLatestPrices(Map.of(itemA, unavailable, itemB, available));
        when(priceConverter.convert(new BigDecimal("50"), "USD", "USD"))
                .thenReturn(new ConvertedAmount(new BigDecimal("50"), null, false));
        when(priceConverter.convert(new BigDecimal("55"), "USD", "USD"))
                .thenReturn(new ConvertedAmount(new BigDecimal("55"), null, false));

        ProductSummaryResponse summary = service.getAllProducts(PageRequest.of(0, 20), "USD")
                .getContent()
                .get(0);

        assertThat(summary.anyAvailable()).isTrue();
    }

    @Test
    void getAllProducts_propagatesStaleFlagFromConverter() {
        PriceRecord usd = priceRecord(itemA, "100", "USD");
        stubProductWithItems(List.of(itemA));
        stubLatestPrices(Map.of(itemA, usd));
        when(priceConverter.convert(new BigDecimal("100"), "USD", "ILS"))
                .thenReturn(new ConvertedAmount(new BigDecimal("363.6364"), TODAY.minusDays(10), true));

        ProductSummaryResponse summary = service.getAllProducts(PageRequest.of(0, 20), "ILS")
                .getContent()
                .get(0);

        assertThat(summary.conversionStale()).isTrue();
        assertThat(summary.conversionAsOf()).isEqualTo(TODAY.minusDays(10));
    }

    // --- getProduct ---

    @Test
    void getProduct_notFound_throwsException() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProduct(99L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getProduct_found_includesLatestPricePerItem() {
        PriceRecord latest = priceRecord(itemA, "999.99", "USD");
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findByProduct(product)).thenReturn(List.of(itemA));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(itemA))
                .thenReturn(Optional.of(latest));

        ProductDetailResponse detail = service.getProduct(1L);

        assertThat(detail.id()).isEqualTo(1L);
        assertThat(detail.trackedItems()).hasSize(1);
        assertThat(detail.trackedItems().get(0).currentPrice()).isEqualByComparingTo("999.99");
        assertThat(detail.trackedItems().get(0).currency()).isEqualTo("USD");
    }

    @Test
    void getProduct_itemWithNoPrice_currentPriceIsNull() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findByProduct(product)).thenReturn(List.of(itemA));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(itemA))
                .thenReturn(Optional.empty());

        ProductDetailResponse detail = service.getProduct(1L);

        assertThat(detail.trackedItems().get(0).currentPrice()).isNull();
    }

    // --- getPriceHistory windowing ---

    @Test
    void getPriceHistory_noBounds_defaultsToWindowEndingNow() {
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(itemA));
        when(priceRecordRepository.findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
                        eq(itemA), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        service.getPriceHistory(1L, 1L, null, null);

        ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(priceRecordRepository)
                .findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
                        eq(itemA), fromCaptor.capture(), toCaptor.capture());
        assertThat(toCaptor.getValue()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
        assertThat(fromCaptor.getValue())
                .isCloseTo(Instant.now().minus(90, ChronoUnit.DAYS), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void getPriceHistory_fromOnly_defaultsToToNow() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(itemA));
        when(priceRecordRepository.findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
                        eq(itemA), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        service.getPriceHistory(1L, 1L, from, null);

        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(priceRecordRepository)
                .findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(eq(itemA), eq(from), toCaptor.capture());
        assertThat(toCaptor.getValue()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void getPriceHistory_toOnly_defaultsFromWindowDaysBefore() {
        Instant to = Instant.parse("2026-04-01T00:00:00Z");
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(itemA));
        when(priceRecordRepository.findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
                        eq(itemA), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        service.getPriceHistory(1L, 1L, null, to);

        ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(priceRecordRepository)
                .findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(eq(itemA), fromCaptor.capture(), eq(to));
        assertThat(fromCaptor.getValue()).isEqualTo(to.minus(90, ChronoUnit.DAYS));
    }

    @Test
    void getPriceHistory_bothBounds_usesExplicitBounds() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(itemA));
        when(priceRecordRepository.findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(itemA, from, to))
                .thenReturn(List.of());

        service.getPriceHistory(1L, 1L, from, to);

        verify(priceRecordRepository).findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(itemA, from, to);
    }

    @Test
    void getPriceHistory_rangeExceedsMaxYears_clampsFrom() {
        Instant to = Instant.parse("2026-04-01T00:00:00Z");
        Instant farBack = to.minus(365L * 3, ChronoUnit.DAYS);
        Instant expectedFrom = to.minus(365L * 2, ChronoUnit.DAYS);
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(itemA));
        when(priceRecordRepository.findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
                        eq(itemA), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        service.getPriceHistory(1L, 1L, farBack, to);

        verify(priceRecordRepository).findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(itemA, expectedFrom, to);
    }

    @Test
    void getPriceHistory_wrongProduct_throwsNotFound() {
        Product other = Product.builder().id(99L).name("Other").build();
        TrackedItem foreignItem = TrackedItem.builder()
                .id(1L)
                .url("http://x.com")
                .shopName("x")
                .product(other)
                .build();
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(foreignItem));

        assertThatThrownBy(() -> service.getPriceHistory(1L, 1L, null, null))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getPriceHistory_mapsExtractionSourceAsString() {
        PriceRecord record = priceRecord(itemA, "100", "USD");
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(itemA));
        when(priceRecordRepository.findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(itemA, from, to))
                .thenReturn(List.of(record));

        PriceHistoryResponse response = service.getPriceHistory(1L, 1L, from, to);

        assertThat(response.history().get(0).extractionSource()).isEqualTo("STRUCTURED");
    }

    private void stubProductWithItems(List<TrackedItem> items) {
        when(productRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product)));
        when(trackedItemRepository.findByProduct(product)).thenReturn(items);
    }

    private void stubLatestPrices(Map<TrackedItem, PriceRecord> prices) {
        prices.forEach((item, price) -> when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.of(price)));
    }

    private void stubIdentityConversion(String amount, String currency) {
        BigDecimal value = new BigDecimal(amount);
        when(priceConverter.convert(value, currency, currency)).thenReturn(new ConvertedAmount(value, null, false));
    }

    private PriceRecord priceRecord(TrackedItem item, String price, String currency) {
        return PriceRecord.builder()
                .price(new BigDecimal(price))
                .currency(currency)
                .available(true)
                .extractionSource(ExtractionSource.STRUCTURED)
                .trackedItem(item)
                .build();
    }
}
