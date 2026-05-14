package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.PriceHistoryResponse;
import com.np.pricehunt.backend.dto.ProductDetailResponse;
import com.np.pricehunt.backend.dto.ProductSummaryResponse;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.temporal.ChronoUnit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class ProductQueryServiceTest {

    @Mock private ProductRepository productRepository;
    @Mock private TrackedItemRepository trackedItemRepository;
    @Mock private PriceRecordRepository priceRecordRepository;

    private ProductQueryService service;

    private Product product;
    private TrackedItem itemA;
    private TrackedItem itemB;

    @BeforeEach
    void setUp() {
        service = new ProductQueryService(productRepository, trackedItemRepository, priceRecordRepository, 90);
        product = Product.builder().id(1L).name("Laptop").build();
        itemA = TrackedItem.builder().id(1L).url("https://amazon.com/dp/1").shopName("amazon.com").product(product).build();
        itemB = TrackedItem.builder().id(2L).url("https://bestbuy.com/p/1").shopName("bestbuy.com").product(product).build();
    }

    // --- getAllProducts ---

    @Test
    void getAllProducts_singleCurrency_computesBestPrice() {
        PriceRecord cheapest = priceRecord(itemA, "49.99", "USD");
        PriceRecord pricier = priceRecord(itemB, "59.99", "USD");
        when(productRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product)));
        when(trackedItemRepository.findByProduct(product)).thenReturn(List.of(itemA, itemB));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(itemA)).thenReturn(Optional.of(cheapest));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(itemB)).thenReturn(Optional.of(pricier));

        Page<ProductSummaryResponse> page = service.getAllProducts(PageRequest.of(0, 20));

        ProductSummaryResponse summary = page.getContent().get(0);
        assertThat(summary.bestPrice()).isEqualByComparingTo("49.99");
        assertThat(summary.bestPriceCurrency()).isEqualTo("USD");
        assertThat(summary.bestPriceShop()).isEqualTo("amazon.com");
        assertThat(summary.mixedCurrencies()).isFalse();
        assertThat(summary.trackedStoreCount()).isEqualTo(2);
    }

    @Test
    void getAllProducts_mixedCurrencies_nullBestPrice() {
        PriceRecord usd = priceRecord(itemA, "49.99", "USD");
        PriceRecord eur = priceRecord(itemB, "45.00", "EUR");
        when(productRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product)));
        when(trackedItemRepository.findByProduct(product)).thenReturn(List.of(itemA, itemB));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(itemA)).thenReturn(Optional.of(usd));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(itemB)).thenReturn(Optional.of(eur));

        ProductSummaryResponse summary = service.getAllProducts(PageRequest.of(0, 20)).getContent().get(0);

        assertThat(summary.bestPrice()).isNull();
        assertThat(summary.bestPriceCurrency()).isNull();
        assertThat(summary.mixedCurrencies()).isTrue();
    }

    @Test
    void getAllProducts_noItemsHavePrices_nullBestPrice() {
        when(productRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product)));
        when(trackedItemRepository.findByProduct(product)).thenReturn(List.of(itemA, itemB));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(itemA)).thenReturn(Optional.empty());
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(itemB)).thenReturn(Optional.empty());

        ProductSummaryResponse summary = service.getAllProducts(PageRequest.of(0, 20)).getContent().get(0);

        assertThat(summary.bestPrice()).isNull();
        assertThat(summary.mixedCurrencies()).isFalse();
        assertThat(summary.trackedStoreCount()).isEqualTo(2);
    }

    @Test
    void getAllProducts_anyAvailable_trueWhenAtLeastOneItemInStock() {
        PriceRecord unavailable = PriceRecord.builder().price(new BigDecimal("50")).currency("USD").available(false)
                .extractionSource(ExtractionSource.STRUCTURED).trackedItem(itemA).build();
        PriceRecord available = PriceRecord.builder().price(new BigDecimal("55")).currency("USD").available(true)
                .extractionSource(ExtractionSource.STRUCTURED).trackedItem(itemB).build();
        when(productRepository.findAll(any(org.springframework.data.domain.Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(product)));
        when(trackedItemRepository.findByProduct(product)).thenReturn(List.of(itemA, itemB));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(itemA)).thenReturn(Optional.of(unavailable));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(itemB)).thenReturn(Optional.of(available));

        ProductSummaryResponse summary = service.getAllProducts(PageRequest.of(0, 20)).getContent().get(0);

        assertThat(summary.anyAvailable()).isTrue();
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
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(itemA)).thenReturn(Optional.of(latest));

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
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(itemA)).thenReturn(Optional.empty());

        ProductDetailResponse detail = service.getProduct(1L);

        assertThat(detail.trackedItems().get(0).currentPrice()).isNull();
    }

    // --- getPriceHistory windowing ---

    @Test
    void getPriceHistory_noBounds_defaultsToWindowEndingNow() {
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(itemA));
        when(priceRecordRepository.findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
                eq(itemA), any(Instant.class), any(Instant.class))).thenReturn(List.of());

        service.getPriceHistory(1L, 1L, null, null);

        ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(priceRecordRepository).findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
                eq(itemA), fromCaptor.capture(), toCaptor.capture());
        assertThat(toCaptor.getValue()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
        assertThat(fromCaptor.getValue()).isCloseTo(Instant.now().minus(90, ChronoUnit.DAYS), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void getPriceHistory_fromOnly_defaultsToToNow() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(itemA));
        when(priceRecordRepository.findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
                eq(itemA), any(Instant.class), any(Instant.class))).thenReturn(List.of());

        service.getPriceHistory(1L, 1L, from, null);

        ArgumentCaptor<Instant> toCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(priceRecordRepository).findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
                eq(itemA), eq(from), toCaptor.capture());
        assertThat(toCaptor.getValue()).isCloseTo(Instant.now(), within(5, ChronoUnit.SECONDS));
    }

    @Test
    void getPriceHistory_toOnly_defaultsFromWindowDaysBefore() {
        Instant to = Instant.parse("2026-04-01T00:00:00Z");
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(itemA));
        when(priceRecordRepository.findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
                eq(itemA), any(Instant.class), any(Instant.class))).thenReturn(List.of());

        service.getPriceHistory(1L, 1L, null, to);

        ArgumentCaptor<Instant> fromCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(priceRecordRepository).findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
                eq(itemA), fromCaptor.capture(), eq(to));
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
                eq(itemA), any(Instant.class), any(Instant.class))).thenReturn(List.of());

        service.getPriceHistory(1L, 1L, farBack, to);

        verify(priceRecordRepository).findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
                itemA, expectedFrom, to);
    }

    @Test
    void getPriceHistory_wrongProduct_throwsNotFound() {
        Product other = Product.builder().id(99L).name("Other").build();
        TrackedItem foreignItem = TrackedItem.builder().id(1L).url("http://x.com").shopName("x").product(other).build();
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
