package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.config.PriceHistoryProperties;
import com.np.pricehunt.backend.config.PriceTrendProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.ShopNameSource;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.PriceHistoryResponse;
import com.np.pricehunt.backend.dto.ProductDetailResponse;
import com.np.pricehunt.backend.dto.ProductListingResponse;
import com.np.pricehunt.backend.dto.TrackedItemSummary;
import com.np.pricehunt.backend.exception.NotFoundException;
import com.np.pricehunt.backend.exception.ValidationException;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.repository.projection.ListingLatestObservationRow;
import com.np.pricehunt.backend.service.fx.ConvertedAmount;
import com.np.pricehunt.backend.service.fx.PriceConverter;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProductQueryServiceTest {

    private static final Instant NOW = LocalDate.of(2026, 5, 24).atTime(12, 0).toInstant(ZoneOffset.UTC);
    private static final int TTL_DAYS = 7;
    private static final String ILS = "ILS";
    private static final String USD = "USD";

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

    @BeforeEach
    void setUp() {
        service = new ProductQueryService(
                productRepository,
                trackedItemRepository,
                priceRecordRepository,
                new PriceHistoryProperties(90),
                new PriceTrendProperties(30, 730, TTL_DAYS),
                priceConverter,
                Clock.fixed(NOW, ZoneOffset.UTC));
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

        assertThatThrownBy(() -> service.getProduct(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getProduct_found_includesLatestPricePerItem() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findListingsWithLatestObservation(1L, NOW))
                .thenReturn(List.of(row(1L, "Amazon", ShopNameSource.MAPPING, "999.99", USD, daysAgo(1))));

        ProductDetailResponse detail = service.getProduct(1L);

        assertThat(detail.id()).isEqualTo(1L);
        assertThat(detail.trackedItems()).hasSize(1);
        TrackedItemSummary summary = detail.trackedItems().get(0);
        assertThat(summary.currentPrice()).isEqualTo("999.9900");
        assertThat(summary.currency()).isEqualTo(USD);
        assertThat(summary.shopNameSource()).isEqualTo(ShopNameSource.MAPPING);
    }

    @Test
    void getProduct_itemWithNoPrice_currentPriceIsNullAndAvailabilityUnknown() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findListingsWithLatestObservation(1L, NOW))
                .thenReturn(List.of(neverObserved(1L, "Amazon")));

        ProductDetailResponse detail = service.getProduct(1L);

        assertThat(detail.trackedItems().get(0).currentPrice()).isNull();
        assertThat(detail.trackedItems().get(0).availability()).isEqualTo(AvailabilityStatus.UNKNOWN);
    }

    @Test
    void getProduct_keepsItsRawMeaning_expiredAndUnavailableObservationsStayVisible() {
        // The detail endpoint never applied the carry-forward rule; sharing the panel's query must not
        // quietly change that (#157). Only the listings panel prunes.
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findListingsWithLatestObservation(1L, NOW))
                .thenReturn(List.of(
                        row(1L, "TMS", "8890", ILS, daysAgo(9)),
                        row(2L, "KSP", "1849", ILS, daysAgo(1), AvailabilityStatus.UNAVAILABLE)));

        ProductDetailResponse detail = service.getProduct(1L);

        assertThat(detail.trackedItems().get(0).currentPrice()).isEqualTo("8890.0000");
        assertThat(detail.trackedItems().get(1).currentPrice()).isEqualTo("1849.0000");
        assertThat(detail.trackedItems().get(1).availability()).isEqualTo(AvailabilityStatus.UNAVAILABLE);
    }

    // --- getListings ---

    @Nested
    class GetListings {

        @Test
        void unknownProduct_is404_beforeAnyQueryRuns() {
            when(productRepository.findById(99L)).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getListings(99L, ILS)).isInstanceOf(NotFoundException.class);
            verifyNoInteractions(trackedItemRepository);
        }

        @Test
        void mapsOriginalAndConvertedSideBySide() {
            stubProduct(row(1L, "Amazon", "102", USD, daysAgo(1)));
            when(priceConverter.convert(new BigDecimal("102"), USD, ILS))
                    .thenReturn(new ConvertedAmount(new BigDecimal("382.0000"), LocalDate.of(2026, 5, 23), false));

            ProductListingResponse listing = service.getListings(1L, ILS).get(0);

            assertThat(listing.trackedItemId()).isEqualTo(1L);
            assertThat(listing.shopName()).isEqualTo("Amazon");
            assertThat(listing.url()).isEqualTo("https://shop.example/1");
            assertThat(listing.priceOriginal()).isEqualTo("102.0000");
            assertThat(listing.priceOriginalCurrency()).isEqualTo(USD);
            assertThat(listing.priceConverted()).isEqualTo("382.0000");
            assertThat(listing.priceConvertedCurrency()).isEqualTo(ILS);
            assertThat(listing.conversionStale()).isFalse();
            assertThat(listing.availability()).isEqualTo(AvailabilityStatus.AVAILABLE);
            assertThat(listing.lastChecked()).isEqualTo(daysAgo(1));
        }

        @Test
        void staleRateIsReportedOnTheListing() {
            stubProduct(row(1L, "Amazon", "102", USD, daysAgo(1)));
            when(priceConverter.convert(any(), eq(USD), eq(ILS)))
                    .thenReturn(new ConvertedAmount(new BigDecimal("382.0000"), LocalDate.of(2026, 5, 1), true));

            assertThat(service.getListings(1L, ILS).get(0).conversionStale()).isTrue();
        }

        @Test
        void unconvertible_keepsTheOriginalAndNullsOnlyTheConvertedSide() {
            stubProduct(row(1L, "Amazon", "102", USD, daysAgo(1)));
            when(priceConverter.convert(any(), eq(USD), eq(ILS))).thenReturn(null);

            ProductListingResponse listing = service.getListings(1L, ILS).get(0);

            assertThat(listing.priceOriginal()).isEqualTo("102.0000");
            assertThat(listing.priceOriginalCurrency()).isEqualTo(USD);
            assertThat(listing.priceConverted()).isNull();
            assertThat(listing.priceConvertedCurrency()).isNull();
            assertThat(listing.conversionStale()).isFalse();
        }

        @Test
        void neverObserved_isUnpricedAndUnknown_andNeverReachesTheConverter() {
            stubProduct(neverObserved(1L, "Ivory"));

            ProductListingResponse listing = service.getListings(1L, ILS).get(0);

            assertThat(listing.priceOriginal()).isNull();
            assertThat(listing.priceConverted()).isNull();
            assertThat(listing.availability()).isEqualTo(AvailabilityStatus.UNKNOWN);
            assertThat(listing.lastChecked()).isNull();
            verifyNoInteractions(priceConverter);
        }

        @Test
        void observationOlderThanTheTtl_isTreatedAsAbsent_butLastCheckedSurvives() {
            // The row's availability count already treats this listing as UNKNOWN with no price; the
            // panel must say the same thing, and "9 days ago" is what tells the user why.
            stubProduct(row(1L, "TMS", "8890", ILS, daysAgo(9)));

            ProductListingResponse listing = service.getListings(1L, ILS).get(0);

            assertThat(listing.priceOriginal()).isNull();
            assertThat(listing.priceConverted()).isNull();
            assertThat(listing.availability()).isEqualTo(AvailabilityStatus.UNKNOWN);
            assertThat(listing.lastChecked()).isEqualTo(daysAgo(9));
            verifyNoInteractions(priceConverter);
        }

        @Test
        void observationExactlyTtlOld_isStillCurrent() {
            stubProduct(row(1L, "KSP", "1299", ILS, daysAgo(TTL_DAYS)));
            stubIdentityConversion();

            assertThat(service.getListings(1L, ILS).get(0).priceConverted()).isEqualTo("1299.0000");
        }

        @Test
        void outOfStockListing_keepsItsPriceAndBadge() {
            // Unlike the row's headline, the panel is where the user sees the out-of-stock price.
            stubProduct(row(1L, "KSP", "1849", ILS, daysAgo(1), AvailabilityStatus.UNAVAILABLE));
            stubIdentityConversion();

            ProductListingResponse listing = service.getListings(1L, ILS).get(0);

            assertThat(listing.priceOriginal()).isEqualTo("1849.0000");
            assertThat(listing.availability()).isEqualTo(AvailabilityStatus.UNAVAILABLE);
        }

        @Test
        void nonPositivePrice_losesThePriceButKeepsItsAvailability() {
            // A hand-inserted 0.00 must not sort to the top; the row's availability count never looked
            // at the price, so the badge must still match it.
            stubProduct(row(1L, "KSP", "0", ILS, daysAgo(1), AvailabilityStatus.AVAILABLE));

            ProductListingResponse listing = service.getListings(1L, ILS).get(0);

            assertThat(listing.priceOriginal()).isNull();
            assertThat(listing.priceConverted()).isNull();
            assertThat(listing.availability()).isEqualTo(AvailabilityStatus.AVAILABLE);
            verifyNoInteractions(priceConverter);
        }

        @Test
        void order_notOutOfStockFirst_thenConvertedAscending_unpricedLast_tiesById() {
            stubProduct(
                    row(1L, "Bug", "1370", ILS, daysAgo(1)),
                    row(2L, "Cheap-but-out", "900", ILS, daysAgo(1), AvailabilityStatus.UNAVAILABLE),
                    row(3L, "Amazon", "300", USD, daysAgo(1)),
                    neverObserved(4L, "Ivory"),
                    row(5L, "KSP", "1299", ILS, daysAgo(1)),
                    row(6L, "KSP-tie", "1299", ILS, daysAgo(1)),
                    row(7L, "Unknown-stock", "1250", ILS, daysAgo(1), AvailabilityStatus.UNKNOWN));
            stubIdentityConversion();
            // The USD listing converts to the most expensive amount — a raw sort would put it first.
            when(priceConverter.convert(new BigDecimal("300"), USD, ILS))
                    .thenReturn(new ConvertedAmount(new BigDecimal("1120.0000"), LocalDate.of(2026, 5, 23), false));

            List<Long> order = service.getListings(1L, ILS).stream()
                    .map(ProductListingResponse::trackedItemId)
                    .toList();

            assertThat(order).containsExactly(3L, 7L, 5L, 6L, 1L, 4L, 2L);
        }

        @Test
        void sortsExactDecimals_notWireStrings() {
            // "1000.0000" < "999.0000" lexically; the comparator must run before formatting.
            stubProduct(row(1L, "A", "1000", ILS, daysAgo(1)), row(2L, "B", "999", ILS, daysAgo(1)));
            stubIdentityConversion();

            List<Long> order = service.getListings(1L, ILS).stream()
                    .map(ProductListingResponse::trackedItemId)
                    .toList();

            assertThat(order).containsExactly(2L, 1L);
        }

        private void stubProduct(ListingLatestObservationRow... rows) {
            when(productRepository.findById(1L)).thenReturn(Optional.of(product));
            when(trackedItemRepository.findListingsWithLatestObservation(1L, NOW))
                    .thenReturn(List.of(rows));
        }

        private void stubIdentityConversion() {
            when(priceConverter.convert(any(), eq(ILS), eq(ILS)))
                    .thenAnswer(inv -> new ConvertedAmount(inv.getArgument(0), LocalDate.of(2026, 5, 24), false));
        }
    }

    // --- getPriceHistory windowing ---

    @Test
    void getPriceHistory_noBounds_defaultsToWindowEndingNow() {
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(itemA));
        when(priceRecordRepository.findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
                        eq(itemA), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        service.getPriceHistory(1L, 1L, null, null);

        verify(priceRecordRepository)
                .findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(itemA, NOW.minus(90, ChronoUnit.DAYS), NOW);
    }

    @Test
    void getPriceHistory_fromOnly_defaultsToToNow() {
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(itemA));
        when(priceRecordRepository.findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
                        eq(itemA), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of());

        service.getPriceHistory(1L, 1L, from, null);

        verify(priceRecordRepository).findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(itemA, from, NOW);
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
                .isInstanceOfSatisfying(ValidationException.class, ex -> {
                    assertThat(ex.getMessage()).isEqualTo("'from' timestamp cannot be after 'to' timestamp");
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

        assertThatThrownBy(() -> service.getPriceHistory(1L, 1L, null, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void getPriceHistory_mapsExtractionSourceAsString() {
        PriceRecord observation = priceRecord(itemA, "100", "USD");
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(itemA));
        when(priceRecordRepository.findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(itemA, from, to))
                .thenReturn(List.of(observation));

        PriceHistoryResponse response = service.getPriceHistory(1L, 1L, from, to);

        assertThat(response.history().get(0).extractionSource()).isEqualTo("STRUCTURED");
    }

    @Test
    void getPriceHistory_formatsPriceAsAFixedScaleDecimalString() {
        // Seeded at scale 0, so a mapper that merely stringified the BigDecimal would emit "100" —
        // this pins that it goes through WireMoney (#175).
        PriceRecord observation = priceRecord(itemA, "100", "USD");
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(itemA));
        when(priceRecordRepository.findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(itemA, from, to))
                .thenReturn(List.of(observation));

        PriceHistoryResponse response = service.getPriceHistory(1L, 1L, from, to);

        assertThat(response.history().get(0).price()).isEqualTo("100.0000");
    }

    // --- fixtures ---

    private static Instant daysAgo(long days) {
        return NOW.minus(days, ChronoUnit.DAYS);
    }

    private static ListingLatestObservationRow row(
            Long id, String shop, String price, String currency, Instant observedAt) {
        return row(id, shop, ShopNameSource.DETECTED, price, currency, observedAt, AvailabilityStatus.AVAILABLE);
    }

    private static ListingLatestObservationRow row(
            Long id, String shop, String price, String currency, Instant observedAt, AvailabilityStatus availability) {
        return row(id, shop, ShopNameSource.DETECTED, price, currency, observedAt, availability);
    }

    private static ListingLatestObservationRow row(
            Long id, String shop, ShopNameSource source, String price, String currency, Instant observedAt) {
        return row(id, shop, source, price, currency, observedAt, AvailabilityStatus.AVAILABLE);
    }

    private static ListingLatestObservationRow row(
            Long id,
            String shop,
            ShopNameSource source,
            String price,
            String currency,
            Instant observedAt,
            AvailabilityStatus availability) {
        return new ObservationRow(
                id,
                "https://shop.example/" + id,
                shop,
                source,
                observedAt,
                new BigDecimal(price),
                currency,
                availability,
                observedAt);
    }

    /** A listing with no qualifying record: the outer join left every observation column null. */
    private static ListingLatestObservationRow neverObserved(Long id, String shop) {
        return new ObservationRow(
                id, "https://shop.example/" + id, shop, ShopNameSource.DETECTED, null, null, null, null, null);
    }

    private record ObservationRow(
            Long trackedItemId,
            String url,
            String shopName,
            ShopNameSource shopNameSource,
            Instant lastChecked,
            BigDecimal price,
            String currency,
            AvailabilityStatus availability,
            Instant observedAt)
            implements ListingLatestObservationRow {
        @Override
        public Long getTrackedItemId() {
            return trackedItemId;
        }

        @Override
        public String getUrl() {
            return url;
        }

        @Override
        public String getShopName() {
            return shopName;
        }

        @Override
        public ShopNameSource getShopNameSource() {
            return shopNameSource;
        }

        @Override
        public Instant getLastChecked() {
            return lastChecked;
        }

        @Override
        public BigDecimal getPrice() {
            return price;
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
