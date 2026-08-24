package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.client.ScraperClient;
import com.np.pricehunt.backend.config.PriceTrackingProperties;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.ShopNameSource;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.TrackRequest;
import com.np.pricehunt.backend.observability.ScrapeAttemptRecorder;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.repository.projection.TrackedItemRefreshView;
import com.np.pricehunt.backend.service.ratelimit.RefreshCooldownLimiter;
import com.np.pricehunt.backend.validator.UrlValidator;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

/**
 * Verifies the #139 validation chokepoint wiring in {@link ProductTrackingService}: refresh + scheduled
 * paths validate the stored URL via {@code validate} BEFORE any downstream work; the track path checks
 * product existence, then validates once (no chokepoint re-check); and a rejection short-circuits before
 * scrape/shop-name/persistence while still consuming the cooldown.
 */
@ExtendWith(MockitoExtension.class)
class ProductTrackingServiceSsrfChokepointTest {

    private static final PriceTrackingProperties TRACKING_PROPERTIES =
            new PriceTrackingProperties(200, Duration.ofMinutes(1), 20);

    private static final String URL = "https://example.com/item";

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

        product = Product.builder().id(1L).name("Test Product").build();
        item = TrackedItem.builder()
                .id(1L)
                .url(URL)
                .shopName("example.com")
                .product(product)
                .build();
    }

    private static final TrackedItemRefreshView VIEW = new TrackedItemRefreshView(1L, URL, null);

    private void runCallbacksInline() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
    }

    @Test
    void refreshTrackedItem_validates_beforeAnyDownstreamWork() {
        when(trackedItemRepository.findRefreshViewByIdAndProductId(1L, 1L)).thenReturn(Optional.of(VIEW));
        when(cooldownLimiter.tryAcquire(1L)).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL host is not allowed"))
                .when(urlValidator)
                .validate(URL);

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 1L)).isInstanceOf(ResponseStatusException.class);

        // Rejected at the chokepoint → no scrape, no shop-name resolution, no persistence.
        verify(urlValidator).validate(URL);
        verify(scraperClient, never()).scrape(any());
        verify(shopNameResolver, never()).resolve(any(), any());
        verify(priceRecordRepository, never()).save(any());
    }

    @Test
    void refreshTrackedItem_rejection_stillConsumesCooldown() {
        when(trackedItemRepository.findRefreshViewByIdAndProductId(1L, 1L)).thenReturn(Optional.of(VIEW));
        when(cooldownLimiter.tryAcquire(1L)).thenReturn(true);
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL host is not allowed"))
                .when(urlValidator)
                .validate(URL);

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 1L)).isInstanceOf(ResponseStatusException.class);

        // Intentional ordering: the volatile cooldown is consumed BEFORE the chokepoint check, so a blocked
        // refresh still burns the window (locks the ordering against a future reorder).
        verify(cooldownLimiter).tryAcquire(1L);
    }

    @Test
    void scheduledRefresh_validates_beforeScrape() {
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "URL host is not allowed"))
                .when(urlValidator)
                .validate(URL);

        assertThatThrownBy(() -> service.scheduledRefresh(VIEW)).isInstanceOf(ResponseStatusException.class);

        verify(urlValidator).validate(URL);
        verify(scraperClient, never()).scrape(any());
    }

    @Test
    void trackUrl_validatesExactlyOnce() {
        runCallbacksInline();
        when(productRepository.existsById(1L)).thenReturn(true);
        when(productRepository.findForUpdateById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findByUrl(URL)).thenReturn(Optional.of(item));
        when(shopNameResolver.resolve(eq(URL), any()))
                .thenReturn(new ShopNameResolver.Resolved("example.com", ShopNameSource.HOST_FALLBACK, null));
        when(scraperClient.scrape(URL)).thenReturn(null); // cheapest happy path — no extraction/save
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.empty());

        service.trackUrl(1L, new TrackRequest(URL));

        // validate() runs once, before storing the row; the chokepoint re-check is skipped
        // (revalidate=false) so there is no second lookup on the track path.
        verify(urlValidator).validate(URL);
    }

    @Test
    void trackUrl_nonexistentProduct_returns404_withoutValidating() {
        // Qodo #2: the cheap existence check runs BEFORE DNS-based validation, so a bad product id 404s
        // without consuming a resolver slot.
        when(productRepository.existsById(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.trackUrl(999L, new TrackRequest(URL)))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> org.assertj.core.api.Assertions.assertThat(
                                e.getStatusCode())
                        .isEqualTo(HttpStatus.NOT_FOUND));

        verify(urlValidator, never()).validate(any());
        verify(scraperClient, never()).scrape(any());
    }

    @Test
    void trackUrl_nullRequest_returns400_withoutValidating() {
        assertThatThrownBy(() -> service.trackUrl(1L, null))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> org.assertj.core.api.Assertions.assertThat(
                                e.getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        verify(urlValidator, never()).validate(any());
    }
}
