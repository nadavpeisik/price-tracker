package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.config.PriceHistoryProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.PriceHistoryResponse;
import com.np.pricehunt.backend.dto.ProductDetailResponse;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ProductQueryServiceTest {

    private static final Instant NOW = LocalDate.of(2026, 5, 24).atTime(12, 0).toInstant(ZoneOffset.UTC);

    @Mock
    private ProductRepository productRepository;

    @Mock
    private TrackedItemRepository trackedItemRepository;

    @Mock
    private PriceRecordRepository priceRecordRepository;

    private ProductQueryService service;

    private Product product;
    private TrackedItem itemA;

    @BeforeEach
    void setUp() {
        service = new ProductQueryService(
                productRepository, trackedItemRepository, priceRecordRepository, new PriceHistoryProperties(90));
        product = Product.builder().id(1L).name("Laptop").build();
        itemA = TrackedItem.builder()
                .id(1L)
                .url("https://amazon.com/dp/1")
                .shopName("amazon.com")
                .product(product)
                .build();
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
        assertThat(detail.trackedItems().get(0).currentPrice()).isEqualTo("999.9900");
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
    void getPriceHistory_fromAfterTo_throwsBadRequest() {
        Instant from = Instant.parse("2026-06-03T00:00:00Z");
        Instant to = Instant.parse("2026-06-01T00:00:00Z");
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(itemA));

        assertThatThrownBy(() -> service.getPriceHistory(1L, 1L, from, to))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(ex.getReason()).isEqualTo("'from' timestamp cannot be after 'to' timestamp");
                });
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

    @Test
    void getPriceHistory_formatsPriceAsAFixedScaleDecimalString() {
        // Seeded at scale 0, so a mapper that merely stringified the BigDecimal would emit "100" —
        // this pins that it goes through WireMoney (#175).
        PriceRecord record = priceRecord(itemA, "100", "USD");
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(itemA));
        when(priceRecordRepository.findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(itemA, from, to))
                .thenReturn(List.of(record));

        PriceHistoryResponse response = service.getPriceHistory(1L, 1L, from, to);

        assertThat(response.history().get(0).price()).isEqualTo("100.0000");
    }

    private PriceRecord priceRecord(TrackedItem item, String price, String currency) {
        return priceRecord(item, price, currency, AvailabilityStatus.AVAILABLE, NOW.minusSeconds(3600));
    }

    private PriceRecord priceRecord(
            TrackedItem item, String price, String currency, AvailabilityStatus availability, Instant observedAt) {
        return PriceRecord.builder()
                .price(new BigDecimal(price))
                .currency(currency)
                .availability(availability)
                .extractionSource(ExtractionSource.STRUCTURED)
                .trackedItem(item)
                .timestamp(observedAt)
                .build();
    }
}
