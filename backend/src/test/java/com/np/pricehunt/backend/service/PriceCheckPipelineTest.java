package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.client.ScraperClient;
import com.np.pricehunt.backend.config.PriceTrackingProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.PriceInfo;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import com.np.pricehunt.backend.dto.TrackResponse;
import com.np.pricehunt.backend.exception.ValidationException;
import com.np.pricehunt.backend.observability.ScrapeAttemptRecorder;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.repository.projection.TrackedItemRefreshView;
import com.np.pricehunt.backend.validator.UrlValidator;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pipeline's entry points and the #139 chokepoint: a refresh-style check revalidates the stored
 * URL before any downstream work, the scheduler's path does the same, the track-style check does not
 * resolve again, and what gets persisted is normalized. Validation outcomes are covered by
 * {@link PriceCheckPipelineValidationTest}, naming by {@link PriceCheckPipelineShopNameTest}.
 */
@ExtendWith(MockitoExtension.class)
class PriceCheckPipelineTest {

    private static final PriceTrackingProperties TRACKING_PROPERTIES =
            new PriceTrackingProperties(200, Duration.ofMinutes(1), 20);
    private static final long LISTING_ID = 1L;
    private static final String URL = "https://example.com/item";
    private static final TrackedItemRefreshView VIEW = new TrackedItemRefreshView(1L, URL, null);

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
    private ScrapeAttemptRecorder scrapeAttemptRecorder;

    private PriceCheckPipeline pipeline;
    private TrackedItem item;

    @BeforeEach
    void setUp() {
        pipeline = new PriceCheckPipeline(
                trackedItemRepository,
                priceRecordRepository,
                extractionService,
                scraperClient,
                transactionTemplate,
                urlValidator,
                shopNameAssignment,
                scrapeAttemptRecorder,
                new PriceValidator(TRACKING_PROPERTIES));
        Product product = Product.builder().id(1L).name("Test Product").build();
        item = TrackedItem.builder()
                .id(1L)
                .url(URL)
                .shopName("example.com")
                .product(product)
                .build();
    }

    private void runCallbacksInline() {
        when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
    }

    private void stubPersistReadsEmpty() {
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(priceRecordRepository.findFirstByTrackedItemOrderByObservedAtDesc(item))
                .thenReturn(Optional.empty());
    }

    private static ScrapeResponse structured(String price, String currency) {
        return new ScrapeResponse(
                ExtractionSource.STRUCTURED,
                new ScrapeResponse.PriceData(new BigDecimal(price), currency, AvailabilityStatus.AVAILABLE),
                null,
                null,
                null);
    }

    @Test
    void refreshStyleCheck_validates_beforeAnyDownstreamWork() {
        doThrow(new ValidationException("URL host is not allowed"))
                .when(urlValidator)
                .validate(URL);

        assertThatThrownBy(() -> pipeline.checkPrice(LISTING_ID, URL, true)).isInstanceOf(ValidationException.class);

        // Rejected at the chokepoint → no scrape, no shop-name resolution, no persistence.
        verify(urlValidator).validate(URL);
        verify(scraperClient, never()).scrape(any());
        verify(shopNameAssignment, never()).applyNameFromUrl(any(), any());
        verify(priceRecordRepository, never()).save(any());
    }

    @Test
    void scheduledRefresh_validates_beforeScrape() {
        doThrow(new ValidationException("URL host is not allowed"))
                .when(urlValidator)
                .validate(URL);

        assertThatThrownBy(() -> pipeline.scheduledRefresh(VIEW)).isInstanceOf(ValidationException.class);

        verify(urlValidator).validate(URL);
        verify(scraperClient, never()).scrape(any());
    }

    @Test
    void trackStyleCheck_doesNotResolveTheUrlAgain() {
        runCallbacksInline();
        stubPersistReadsEmpty();
        when(scraperClient.scrape(URL)).thenReturn(null); // cheapest happy path — no extraction/save

        pipeline.checkPrice(LISTING_ID, URL, false);

        // The track path validated the submitted URL before admission; the chokepoint re-check is
        // skipped so there is no second DNS resolution.
        verify(urlValidator, never()).validate(any());
        verify(scraperClient).scrape(URL);
    }

    @Test
    void scheduledRefresh_savesThePrice_evenWhenTheListingWasJustRefreshed() {
        // A user refresh would be on cooldown; the scheduler is the system and has none.
        runCallbacksInline();
        TrackedItem recentItem = TrackedItem.builder()
                .id(1L)
                .url(URL)
                .shopName("example.com")
                .product(item.getProduct())
                .lastChecked(Instant.now().minusSeconds(10))
                .build();
        ScrapeResponse scraped = structured("899.99", "USD");
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(recentItem));
        when(priceRecordRepository.findFirstByTrackedItemOrderByObservedAtDesc(recentItem))
                .thenReturn(Optional.empty());
        when(scraperClient.scrape(URL)).thenReturn(scraped);
        when(extractionService.extractPrice(scraped))
                .thenReturn(new PriceInfo(
                        new BigDecimal("899.99"), "USD", AvailabilityStatus.AVAILABLE, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "observedAt", Instant.now());
            return r;
        });

        TrackResponse response = pipeline.scheduledRefresh(VIEW);

        verify(scraperClient).scrape(URL);
        verify(priceRecordRepository).save(any());
        assertThat(response.currentPrice()).isEqualTo("899.9900");
        // lastChecked is stamped by id, never through the loaded entity (#222): a dirty entity would
        // flush every column and could overwrite a concurrent shop-name change.
        verify(trackedItemRepository).updateLastCheckedById(eq(1L), any(Instant.class));
        assertThat(recentItem.getLastChecked()).isBefore(Instant.now());
    }

    @Test
    void persistedCurrency_isNormalizedToUppercase() {
        runCallbacksInline();
        ScrapeResponse scraped = structured("100.00", "usd");
        stubPersistReadsEmpty();
        when(scraperClient.scrape(URL)).thenReturn(scraped);
        when(extractionService.extractPrice(scraped))
                .thenReturn(new PriceInfo(
                        new BigDecimal("100.00"), "usd", AvailabilityStatus.AVAILABLE, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "observedAt", Instant.now());
            return r;
        });

        pipeline.checkPrice(LISTING_ID, URL, true);

        ArgumentCaptor<PriceRecord> captor = ArgumentCaptor.forClass(PriceRecord.class);
        verify(priceRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
    }

    @Test
    void nullScrape_returnsTheLastKnownGoodObservation_withoutSaving() {
        runCallbacksInline();
        stubPersistReadsEmpty();
        when(scraperClient.scrape(URL)).thenReturn(null);

        TrackResponse response = pipeline.checkPrice(LISTING_ID, URL, true);

        assertThat(response.currentPrice()).isNull();
        verify(priceRecordRepository, never()).save(any());
    }
}
