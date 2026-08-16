package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.np.pricehunt.backend.client.ScraperClient;
import com.np.pricehunt.backend.config.PriceTrackingProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.ShopNameSource;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.*;
import com.np.pricehunt.backend.observability.ScrapeAttemptRecorder;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

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
    private ShopNameResolver shopNameResolver;

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
                shopNameResolver,
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
    void createProduct_savesWithoutCountingTheCatalogue() {
        // No global product cap: the catalogue is a shared canonical set, so its size is a capacity
        // question for whoever operates the system, not something to reject a create over (#172).
        when(productRepository.save(any()))
                .thenReturn(Product.builder().id(2L).name("Laptop").build());

        assertThat(service.createProduct(new CreateProductRequest("Laptop")).id())
                .isEqualTo(2L);

        verify(productRepository, never()).count();
    }

    @Test
    void trackUrl_newUrlAtListingCap_returns409_withoutScraping() {
        when(productRepository.existsById(1L)).thenReturn(true);
        when(productRepository.findForUpdateById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findByUrl("https://amazon.com/dp/2")).thenReturn(Optional.empty());
        when(trackedItemRepository.countByProduct(product)).thenReturn(20L);

        assertThatThrownBy(() -> service.trackUrl(1L, new TrackRequest("https://amazon.com/dp/2")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

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
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.empty());
        when(scraperClient.scrape(item.getUrl())).thenReturn(null);
        when(shopNameResolver.resolve(any(), any()))
                .thenReturn(new ShopNameResolver.Resolved("amazon.com", ShopNameSource.HOST_FALLBACK, null));

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
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.empty());
        when(scraperClient.scrape(item.getUrl())).thenReturn(null);
        when(shopNameResolver.resolve(any(), any()))
                .thenReturn(new ShopNameResolver.Resolved("amazon.com", ShopNameSource.HOST_FALLBACK, null));

        service.trackUrl(1L, new TrackRequest(item.getUrl()));

        verify(productRepository).findForUpdateById(1L);
        verify(productRepository, never()).findById(1L);
    }

    // --- deleteProduct ---

    @Test
    void deleteProduct_notFound_throwsException() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteProduct(99L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(productRepository, never()).delete(any());
    }

    @Test
    void deleteProduct_found_callsDelete() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        service.deleteProduct(1L);

        verify(productRepository).delete(product);
    }

    // --- deleteTrackedItem ---

    @Test
    void deleteTrackedItem_notFound_throwsException() {
        when(trackedItemRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteTrackedItem(1L, 99L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(trackedItemRepository, never()).delete(any());
    }

    @Test
    void deleteTrackedItem_wrongProduct_throwsException() {
        Product other = Product.builder().id(99L).name("Other").build();
        TrackedItem foreignItem = TrackedItem.builder()
                .id(1L)
                .url("http://x.com")
                .shopName("x")
                .product(other)
                .build();
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(foreignItem));

        assertThatThrownBy(() -> service.deleteTrackedItem(1L, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verify(trackedItemRepository, never()).delete(any());
    }

    @Test
    void deleteTrackedItem_found_callsDelete() {
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));

        service.deleteTrackedItem(1L, 1L);

        verify(trackedItemRepository).delete(item);
    }

    // --- updateProduct ---

    @Test
    void updateProduct_notFound_throwsException() {
        when(productRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateProduct(99L, new UpdateProductRequest("New", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void updateProduct_updatesName() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        ProductResponse response = service.updateProduct(1L, new UpdateProductRequest("New Name", null));

        assertThat(product.getName()).isEqualTo("New Name");
        assertThat(response.name()).isEqualTo("New Name");
    }

    @Test
    void updateProduct_blankName_returns400() {
        assertThatThrownBy(() -> service.updateProduct(1L, new UpdateProductRequest("  ", null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(productRepository, never()).findById(any());
    }

    @Test
    void updateProduct_blankNameWithDescription_returns400_noPartialUpdate() {
        assertThatThrownBy(() -> service.updateProduct(1L, new UpdateProductRequest("  ", "A great laptop")))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(productRepository, never()).findById(any());
        assertThat(product.getDescription()).isNull();
    }

    @Test
    void updateProduct_updatesDescription() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        service.updateProduct(1L, new UpdateProductRequest(null, "A great laptop"));

        assertThat(product.getDescription()).isEqualTo("A great laptop");
    }

    @Test
    void updateProduct_returnsLightweightResponse_doesNotFetchTrackedItems() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        service.updateProduct(1L, new UpdateProductRequest("New Name", null));

        verifyNoInteractions(trackedItemRepository, priceRecordRepository);
    }

    @Test
    void updateProduct_clearsDescription_whenEmptyStringPassed() {
        product.setDescription("old description");
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        service.updateProduct(1L, new UpdateProductRequest(null, ""));

        assertThat(product.getDescription()).isNull();
    }

    @Test
    void updateProduct_doesNotCallSave_dirtyCheckingFlushes() {
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));

        service.updateProduct(1L, new UpdateProductRequest("New Name", null));

        verify(productRepository, never()).save(any());
    }

    @Test
    void updateProduct_allFieldsNull_returns400() {
        assertThatThrownBy(() -> service.updateProduct(1L, new UpdateProductRequest(null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(productRepository, never()).findById(any());
    }

    // --- refreshTrackedItem ---

    @Test
    void refreshTrackedItem_recentlyRefreshed_throwsTooManyRequests() {
        TrackedItem recentItem = TrackedItem.builder()
                .id(1L)
                .url("https://amazon.com/dp/1")
                .shopName("amazon.com")
                .product(product)
                .lastChecked(Instant.now().minusSeconds(10))
                .build();
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(recentItem));

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        verify(scraperClient, never()).scrape(any());
        // Durable (DB lastChecked) cooldown rejects before the volatile limiter is consulted.
        verifyNoInteractions(cooldownLimiter);
    }

    @Test
    void refreshTrackedItem_volatileCooldownActive_throwsTooManyRequests() {
        // DB lastChecked is null (durable check passes), but the in-memory limiter rejects —
        // e.g. a rapid retry after a failed scrape. Must 429 before scraping.
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(cooldownLimiter.tryAcquire(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        verify(scraperClient, never()).scrape(any());
    }

    @Test
    void refreshTrackedItem_notFound_throwsException() {
        when(trackedItemRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 99L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

        verifyNoInteractions(cooldownLimiter);
    }

    @Test
    void refreshTrackedItem_wrongProduct_throwsException() {
        Product other = Product.builder().id(99L).name("Other").build();
        TrackedItem foreignItem = TrackedItem.builder()
                .id(1L)
                .url("http://x.com")
                .shopName("x")
                .product(other)
                .build();
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(foreignItem));

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);

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
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(cooldownLimiter.tryAcquire(1L)).thenReturn(true);
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.empty());
        when(scraperClient.scrape(item.getUrl())).thenReturn(scraped);
        when(shopNameResolver.resolve(any(), any()))
                .thenReturn(new ShopNameResolver.Resolved("amazon.com", ShopNameSource.HOST_FALLBACK, null));
        when(extractionService.extractPrice(scraped))
                .thenReturn(new PriceInfo(
                        new BigDecimal("899.99"), "USD", AvailabilityStatus.AVAILABLE, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "timestamp", Instant.now());
            return r;
        });

        TrackResponse response = service.refreshTrackedItem(1L, 1L);

        verify(scraperClient).scrape(item.getUrl());
        assertThat(response.currentPrice()).isEqualTo("899.9900");
    }

    @Test
    void refreshTrackedItem_secondAttemptAfterFailedScrape_throwsTooManyRequests() {
        // First attempt: scraper returns null. lastChecked never bumped on the entity, but the
        // volatile limiter is stamped before the scrape — so the second attempt is rejected by it.
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(cooldownLimiter.tryAcquire(1L)).thenReturn(true, false);
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.empty());
        when(scraperClient.scrape(item.getUrl())).thenReturn(null);
        when(shopNameResolver.resolve(any(), any()))
                .thenReturn(new ShopNameResolver.Resolved("amazon.com", ShopNameSource.HOST_FALLBACK, null));

        TrackResponse first = service.refreshTrackedItem(1L, 1L);
        assertThat(first.currentPrice()).isNull();

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

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
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(recentItem))
                .thenReturn(Optional.empty());
        when(scraperClient.scrape(recentItem.getUrl())).thenReturn(scraped);
        when(shopNameResolver.resolve(any(), any()))
                .thenReturn(new ShopNameResolver.Resolved("amazon.com", ShopNameSource.HOST_FALLBACK, null));
        when(extractionService.extractPrice(scraped))
                .thenReturn(new PriceInfo(
                        new BigDecimal("899.99"), "USD", AvailabilityStatus.AVAILABLE, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "timestamp", Instant.now());
            return r;
        });

        TrackResponse response = service.scheduledRefresh(1L);

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
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(cooldownLimiter.tryAcquire(1L)).thenReturn(true);
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.empty());
        when(scraperClient.scrape(item.getUrl())).thenReturn(scraped);
        when(shopNameResolver.resolve(any(), any()))
                .thenReturn(new ShopNameResolver.Resolved("amazon.com", ShopNameSource.HOST_FALLBACK, null));
        when(extractionService.extractPrice(scraped))
                .thenReturn(new PriceInfo(
                        new BigDecimal("100.00"), "usd", AvailabilityStatus.AVAILABLE, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "timestamp", Instant.now());
            return r;
        });

        service.refreshTrackedItem(1L, 1L);

        org.mockito.ArgumentCaptor<PriceRecord> captor = org.mockito.ArgumentCaptor.forClass(PriceRecord.class);
        verify(priceRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
    }
}
