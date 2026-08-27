package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.np.pricehunt.backend.client.ScraperClient;
import com.np.pricehunt.backend.config.PriceTrackingProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.*;
import com.np.pricehunt.backend.exception.ConflictException;
import com.np.pricehunt.backend.exception.ErrorCode;
import com.np.pricehunt.backend.exception.NotFoundException;
import com.np.pricehunt.backend.exception.RefreshCooldownException;
import com.np.pricehunt.backend.observability.ScrapeAttemptRecorder;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.repository.projection.TrackedItemRefreshView;
import com.np.pricehunt.backend.service.ratelimit.RefreshCooldownLimiter;
import com.np.pricehunt.backend.validator.UrlValidator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class ProductTrackingServiceCrudTest {

    private static final PriceTrackingProperties TRACKING_PROPERTIES =
            new PriceTrackingProperties(200, Duration.ofMinutes(1), 20);

    @Mock
    private ProductRepository productRepository;

    @Mock
    private TrackedItemRepository trackedItemRepository;

    @Mock
    private PriceRecordRepository priceRecordRepository;

    @Mock
    private PriceExtractionService extractionService;

    @Mock
    private ScraperClient scraperClient;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private UrlValidator urlValidator;

    @Mock
    private ShopNameAssignment shopNameAssignment;

    @Mock
    private RefreshCooldownLimiter cooldownLimiter;

    @Mock
    private ScrapeAttemptRecorder scrapeAttemptRecorder;

    private ProductTrackingService service;

    private Product product;
    private TrackedItem item;

    @BeforeEach
    void setUp() {
        service = new ProductTrackingService(
                productRepository,
                trackedItemRepository,
                priceRecordRepository,
                extractionService,
                scraperClient,
                transactionTemplate,
                urlValidator,
                TRACKING_PROPERTIES,
                shopNameAssignment,
                cooldownLimiter,
                Clock.systemUTC(),
                scrapeAttemptRecorder,
                new PriceValidator(TRACKING_PROPERTIES));
        // Run transactionTemplate callbacks inline so phase splits are exercised end-to-end.
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        product = Product.builder().id(1L).name("Laptop").build();
        item = TrackedItem.builder()
                .id(1L)
                .url("https://amazon.com/dp/1")
                .shopName("amazon.com")
                .product(product)
                .build();
    }

    // --- listing admission (#146) ---

    @Test
    void trackUrl_newUrlAtListingCap_returns409_withoutScraping() {
        when(productRepository.existsById(1L)).thenReturn(true);
        when(productRepository.findForUpdateById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findByUrl("https://amazon.com/dp/2")).thenReturn(Optional.empty());
        when(trackedItemRepository.countByProduct(product)).thenReturn(20L);

        assertThatThrownBy(() -> service.trackUrl(1L, new TrackRequest("https://amazon.com/dp/2")))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(ErrorCode.PRODUCT_LISTING_LIMIT_REACHED));

        verify(trackedItemRepository, never()).save(any());
        verify(scraperClient, never()).scrape(any());
    }

    @Test
    void trackUrl_urlOwnedByAnotherProduct_returns409_withItsOwnCode() {
        // Same status as the listing cap, different remedy — the code is what tells them apart (#173).
        Product other = Product.builder().id(2L).name("Other").build();
        TrackedItem theirs = TrackedItem.builder()
                .id(7L)
                .url("https://amazon.com/dp/2")
                .product(other)
                .build();
        when(productRepository.existsById(1L)).thenReturn(true);
        when(productRepository.findForUpdateById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findByUrl("https://amazon.com/dp/2")).thenReturn(Optional.of(theirs));

        assertThatThrownBy(() -> service.trackUrl(1L, new TrackRequest("https://amazon.com/dp/2")))
                .isInstanceOfSatisfying(ConflictException.class, e -> assertThat(e.errorCode())
                        .isEqualTo(ErrorCode.URL_TRACKED_BY_ANOTHER_PRODUCT));

        verify(trackedItemRepository, never()).save(any());
        verify(scraperClient, never()).scrape(any());
    }

    @Test
    void trackUrl_existingUrlAtListingCap_isUnaffected() {
        // Re-tracking a URL the product already has admits no new listing, so the cap must not fire.
        when(productRepository.existsById(1L)).thenReturn(true);
        when(productRepository.findForUpdateById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findByUrl(item.getUrl())).thenReturn(Optional.of(item));
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(priceRecordRepository.findFirstByTrackedItemOrderByObservedAtDesc(item))
                .thenReturn(Optional.empty());
        when(scraperClient.scrape(item.getUrl())).thenReturn(null);

        service.trackUrl(1L, new TrackRequest(item.getUrl()));

        verify(trackedItemRepository, never()).countByProduct(any());
        verify(scraperClient).scrape(item.getUrl());
    }

    @Test
    void trackUrl_loadsParentWithTheWriteLock_notThePlainFinder() {
        // The lock is what serializes admission; a plain findById would leave the count-then-insert
        // racing a concurrent track of the same product.
        when(productRepository.existsById(1L)).thenReturn(true);
        when(productRepository.findForUpdateById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findByUrl(item.getUrl())).thenReturn(Optional.of(item));
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(priceRecordRepository.findFirstByTrackedItemOrderByObservedAtDesc(item))
                .thenReturn(Optional.empty());
        when(scraperClient.scrape(item.getUrl())).thenReturn(null);

        service.trackUrl(1L, new TrackRequest(item.getUrl()));

        verify(productRepository).findForUpdateById(1L);
        verify(productRepository, never()).findById(1L);
    }

    // --- refreshTrackedItem ---

    private static TrackedItemRefreshView viewOf(TrackedItem item) {
        return new TrackedItemRefreshView(item.getId(), item.getUrl(), item.getLastChecked());
    }

    @Test
    void refreshTrackedItem_recentlyRefreshed_throwsTooManyRequests() {
        TrackedItem recentItem = TrackedItem.builder()
                .id(1L)
                .url("https://amazon.com/dp/1")
                .shopName("amazon.com")
                .product(product)
                .lastChecked(Instant.now().minusSeconds(10))
                .build();
        when(trackedItemRepository.findRefreshViewByIdAndProductId(1L, 1L)).thenReturn(Optional.of(viewOf(recentItem)));

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 1L)).isInstanceOf(RefreshCooldownException.class);

        verify(scraperClient, never()).scrape(any());
        // Durable (DB lastChecked) cooldown rejects before the volatile limiter is consulted.
        verifyNoInteractions(cooldownLimiter);
    }

    @Test
    void refreshTrackedItem_volatileCooldownActive_throwsTooManyRequests() {
        // DB lastChecked is null (durable check passes), but the in-memory limiter rejects —
        // e.g. a rapid retry after a failed scrape. Must 429 before scraping.
        when(trackedItemRepository.findRefreshViewByIdAndProductId(1L, 1L)).thenReturn(Optional.of(viewOf(item)));
        when(cooldownLimiter.tryAcquire(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 1L)).isInstanceOf(RefreshCooldownException.class);

        verify(scraperClient, never()).scrape(any());
    }

    @Test
    void refreshTrackedItem_notFound_throwsException() {
        when(trackedItemRepository.findRefreshViewByIdAndProductId(99L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 99L)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(cooldownLimiter);
    }

    @Test
    void refreshTrackedItem_wrongProduct_throwsException() {
        // Ownership is the query's product filter: an item under another product reads as absent.
        when(trackedItemRepository.findRefreshViewByIdAndProductId(1L, 1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 1L)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(cooldownLimiter);
    }

    @Test
    void refreshTrackedItem_found_callsScraper() {
        ScrapeResponse scraped = new ScrapeResponse(
                ExtractionSource.STRUCTURED,
                new ScrapeResponse.PriceData(new BigDecimal("899.99"), "USD", AvailabilityStatus.AVAILABLE),
                null,
                null,
                null);
        when(trackedItemRepository.findRefreshViewByIdAndProductId(1L, 1L)).thenReturn(Optional.of(viewOf(item)));
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(cooldownLimiter.tryAcquire(1L)).thenReturn(true);
        when(priceRecordRepository.findFirstByTrackedItemOrderByObservedAtDesc(item))
                .thenReturn(Optional.empty());
        when(scraperClient.scrape(item.getUrl())).thenReturn(scraped);
        when(extractionService.extractPrice(scraped))
                .thenReturn(new PriceInfo(
                        new BigDecimal("899.99"), "USD", AvailabilityStatus.AVAILABLE, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "observedAt", Instant.now());
            return r;
        });

        when(trackedItemRepository.updateLastCheckedById(eq(1L), any(Instant.class)))
                .thenReturn(1);

        TrackResponse response = service.refreshTrackedItem(1L, 1L);

        verify(scraperClient).scrape(item.getUrl());
        assertThat(response.currentPrice()).isEqualTo("899.9900");
        // lastChecked is stamped by id, never through the loaded entity (#222): a dirty entity would
        // flush every column and could overwrite a concurrent shop-name change.
        verify(trackedItemRepository).updateLastCheckedById(eq(1L), any(Instant.class));
        assertThat(item.getLastChecked()).isNull();
    }

    @Test
    void refreshTrackedItem_secondAttemptAfterFailedScrape_throwsTooManyRequests() {
        // First attempt: scraper returns null. lastChecked never bumped on the entity, but the
        // volatile limiter is stamped before the scrape — so the second attempt is rejected by it.
        when(trackedItemRepository.findRefreshViewByIdAndProductId(1L, 1L)).thenReturn(Optional.of(viewOf(item)));
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(cooldownLimiter.tryAcquire(1L)).thenReturn(true, false);
        when(priceRecordRepository.findFirstByTrackedItemOrderByObservedAtDesc(item))
                .thenReturn(Optional.empty());
        when(scraperClient.scrape(item.getUrl())).thenReturn(null);

        TrackResponse first = service.refreshTrackedItem(1L, 1L);
        assertThat(first.currentPrice()).isNull();

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 1L)).isInstanceOf(RefreshCooldownException.class);

        verify(scraperClient, times(1)).scrape(item.getUrl());
    }

    @Test
    void scheduledRefresh_savesPriceWithoutRateLimitCheck() {
        // Item was just refreshed — a manual refresh would 429, but the scheduler must bypass.
        TrackedItem recentItem = TrackedItem.builder()
                .id(1L)
                .url("https://amazon.com/dp/1")
                .shopName("amazon.com")
                .product(product)
                .lastChecked(Instant.now().minusSeconds(10))
                .build();
        ScrapeResponse scraped = new ScrapeResponse(
                ExtractionSource.STRUCTURED,
                new ScrapeResponse.PriceData(new BigDecimal("899.99"), "USD", AvailabilityStatus.AVAILABLE),
                null,
                null,
                null);
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(recentItem));
        when(priceRecordRepository.findFirstByTrackedItemOrderByObservedAtDesc(recentItem))
                .thenReturn(Optional.empty());
        when(scraperClient.scrape(recentItem.getUrl())).thenReturn(scraped);
        when(extractionService.extractPrice(scraped))
                .thenReturn(new PriceInfo(
                        new BigDecimal("899.99"), "USD", AvailabilityStatus.AVAILABLE, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "observedAt", Instant.now());
            return r;
        });

        TrackResponse response = service.scheduledRefresh(viewOf(recentItem));

        verify(scraperClient).scrape(recentItem.getUrl());
        verify(priceRecordRepository).save(any());
        assertThat(response.currentPrice()).isEqualTo("899.9900");
        // scheduledRefresh is system-initiated and bypasses the user cooldown entirely.
        verifyNoInteractions(cooldownLimiter);
    }

    @Test
    void scrapeAndPersist_normalizesCurrencyToUppercase() {
        ScrapeResponse scraped = new ScrapeResponse(
                ExtractionSource.STRUCTURED,
                new ScrapeResponse.PriceData(new BigDecimal("100.00"), "usd", AvailabilityStatus.AVAILABLE),
                null,
                null,
                null);
        when(trackedItemRepository.findRefreshViewByIdAndProductId(1L, 1L)).thenReturn(Optional.of(viewOf(item)));
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(cooldownLimiter.tryAcquire(1L)).thenReturn(true);
        when(priceRecordRepository.findFirstByTrackedItemOrderByObservedAtDesc(item))
                .thenReturn(Optional.empty());
        when(scraperClient.scrape(item.getUrl())).thenReturn(scraped);
        when(extractionService.extractPrice(scraped))
                .thenReturn(new PriceInfo(
                        new BigDecimal("100.00"), "usd", AvailabilityStatus.AVAILABLE, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "observedAt", Instant.now());
            return r;
        });

        service.refreshTrackedItem(1L, 1L);

        org.mockito.ArgumentCaptor<PriceRecord> captor = org.mockito.ArgumentCaptor.forClass(PriceRecord.class);
        verify(priceRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
    }
}
