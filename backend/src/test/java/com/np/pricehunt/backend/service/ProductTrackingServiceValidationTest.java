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
import com.np.pricehunt.backend.domain.ScrapeFailureCode;
import com.np.pricehunt.backend.domain.ShopNameSource;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.*;
import com.np.pricehunt.backend.exception.ScrapeBlockedException;
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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

@ExtendWith(MockitoExtension.class)
class ProductTrackingServiceValidationTest {

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
    private ScrapeResponse scrapeResponse;

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
                new PriceTrackingProperties(200, Duration.ofMinutes(1)),
                shopNameResolver,
                cooldownLimiter,
                Clock.systemUTC(),
                scrapeAttemptRecorder,
                new PriceValidator(new PriceTrackingProperties(200, Duration.ofMinutes(1))));

        product = Product.builder().id(1L).name("Test Product").build();
        item = TrackedItem.builder()
                .id(1L)
                .url("https://example.com/item")
                .shopName("example.com")
                .product(product)
                .build();
        scrapeResponse = new ScrapeResponse(
                ExtractionSource.STRUCTURED,
                new ScrapeResponse.PriceData(new BigDecimal("100.00"), "USD", AvailabilityStatus.AVAILABLE),
                null,
                null,
                null);

        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findByUrl(any())).thenReturn(Optional.of(item));
        // findById is hit only inside persistResultInTxn — tests that short-circuit
        // before persistence (e.g. ScrapeBlockedException propagation) skip it.
        lenient().when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(scraperClient.scrape(any())).thenReturn(scrapeResponse);
        when(shopNameResolver.resolve(any(), any()))
                .thenReturn(new ShopNameResolver.Resolved("example.com", ShopNameSource.HOST_FALLBACK, null));
    }

    @Test
    void trackUrl_validPrice_noHistory_savesPriceRecord() {
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.empty());
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(
                        new BigDecimal("100.00"), "USD", AvailabilityStatus.AVAILABLE, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "timestamp", Instant.now());
            return r;
        });

        TrackResponse response = service.trackUrl(1L, new TrackRequest("https://example.com/item"));

        assertThat(response.currentPrice()).isEqualByComparingTo("100.00");
        verify(priceRecordRepository).save(any());
        // trackUrl is first-time tracking, not a refresh — the cooldown limiter must not apply. A
        // successful track must NOT record a scrape_attempt (failure-first; #131).
        verifyNoInteractions(cooldownLimiter, scrapeAttemptRecorder);
    }

    @Test
    void trackUrl_validPrice_noHistory_savesExtractionSource() {
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.empty());
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(
                        new BigDecimal("100.00"), "USD", AvailabilityStatus.AVAILABLE, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "timestamp", Instant.now());
            return r;
        });

        service.trackUrl(1L, new TrackRequest("https://example.com/item"));

        ArgumentCaptor<PriceRecord> captor = ArgumentCaptor.forClass(PriceRecord.class);
        verify(priceRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getExtractionSource()).isEqualTo(ExtractionSource.STRUCTURED);
    }

    @Test
    void trackUrl_zeroPrice_skipsSave() {
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.empty());
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(
                        new PriceInfo(BigDecimal.ZERO, "USD", AvailabilityStatus.AVAILABLE, ExtractionSource.FULLTEXT));

        service.trackUrl(1L, new TrackRequest("https://example.com/item"));

        verify(priceRecordRepository, never()).save(any());
        // A rejected price records a VALIDATION_REJECTED scrape_attempt with the precise code (#131).
        verify(scrapeAttemptRecorder)
                .recordValidationRejection(
                        eq(1L), any(), eq(scrapeResponse), eq(ScrapeFailureCode.PRICE_NON_POSITIVE), any());
    }

    @Test
    void trackUrl_negativePrice_skipsSave() {
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.empty());
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(
                        new BigDecimal("-5.00"), "USD", AvailabilityStatus.AVAILABLE, ExtractionSource.FULLTEXT));

        service.trackUrl(1L, new TrackRequest("https://example.com/item"));

        verify(priceRecordRepository, never()).save(any());
    }

    @Test
    void trackUrl_nullCurrency_noHistory_skipsSave() {
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.empty());
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(
                        new BigDecimal("100.00"), null, AvailabilityStatus.AVAILABLE, ExtractionSource.FULLTEXT));

        service.trackUrl(1L, new TrackRequest("https://example.com/item"));

        verify(priceRecordRepository, never()).save(any());
    }

    @Test
    void trackUrl_nullCurrency_withHistory_skipsSave() {
        PriceRecord previous = priceRecord("100.00", "USD");
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.of(previous));
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(
                        new BigDecimal("105.00"), null, AvailabilityStatus.AVAILABLE, ExtractionSource.FULLTEXT));

        service.trackUrl(1L, new TrackRequest("https://example.com/item"));

        verify(priceRecordRepository, never()).save(any());
    }

    @Test
    void trackUrl_currencyChanged_skipsDeltaCheckAndSaves() {
        PriceRecord previous = priceRecord("100.00", "USD");
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.of(previous));
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(
                        new BigDecimal("90.00"), "EUR", AvailabilityStatus.AVAILABLE, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "timestamp", Instant.now());
            return r;
        });

        TrackResponse response = service.trackUrl(1L, new TrackRequest("https://example.com/item"));

        verify(priceRecordRepository).save(any());
        assertThat(response.currency()).isEqualTo("EUR");
    }

    @Test
    void trackUrl_priceWithinUpperDelta_saves() {
        PriceRecord previous = priceRecord("100.00", "USD");
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.of(previous));
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(
                        new BigDecimal("250.00"), "USD", AvailabilityStatus.AVAILABLE, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "timestamp", Instant.now());
            return r;
        });

        service.trackUrl(1L, new TrackRequest("https://example.com/item"));

        verify(priceRecordRepository).save(any());
    }

    @Test
    void trackUrl_priceExceedsUpperDelta_skipsSave() {
        PriceRecord previous = priceRecord("100.00", "USD");
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.of(previous));
        // 400 is 4x previous, exceeds 200% delta (max is 3x)
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(
                        new BigDecimal("400.00"), "USD", AvailabilityStatus.AVAILABLE, ExtractionSource.FULLTEXT));

        service.trackUrl(1L, new TrackRequest("https://example.com/item"));

        verify(priceRecordRepository, never()).save(any());
    }

    @Test
    void trackUrl_priceBelowLowerDelta_skipsSave() {
        PriceRecord previous = priceRecord("100.00", "USD");
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.of(previous));
        // 10 is 1/10 of previous; lower bound is 100/3 ≈ 33.33, so 10 is below it
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(
                        new BigDecimal("10.00"), "USD", AvailabilityStatus.AVAILABLE, ExtractionSource.FULLTEXT));

        service.trackUrl(1L, new TrackRequest("https://example.com/item"));

        verify(priceRecordRepository, never()).save(any());
    }

    @Test
    void trackUrl_blockedScrape_propagatesExceptionAndDoesNotSaveRecord() {
        // When the extraction layer raises ScrapeBlockedException (because the
        // scraper returned ExtractionSource.BLOCKED), trackUrl must propagate it
        // unchanged — no PriceRecord saved, no silent "last known" fallback. The
        // transactional boundary rolls back any uncommitted work; the controller
        // turns the ResponseStatusException into a 502 to the client.
        // Note: we override the extractionService stub rather than the scraperClient
        // stub to avoid shadowing the BeforeEach scrape stub (Mockito strict-stubs).
        // The orchestrator test separately verifies BLOCKED → ScrapeBlockedException.
        String reason = "cloudflare-managed:cf-ray=9fcfc0abcd123456-TLV";
        when(extractionService.extractPrice(scrapeResponse)).thenThrow(new ScrapeBlockedException(reason));

        assertThatThrownBy(() -> service.trackUrl(1L, new TrackRequest("https://example.com/item")))
                .isInstanceOf(ScrapeBlockedException.class)
                .hasMessageContaining(reason);

        verify(priceRecordRepository, never()).save(any());
        // The failure is recorded once (EXTRACTION_FAILED) before the exception propagates (#131).
        verify(scrapeAttemptRecorder)
                .recordExtractionFailure(eq(1L), any(), eq(scrapeResponse), any(ScrapeBlockedException.class));
    }

    @Test
    void trackUrl_recorderThrows_originalExceptionStillPropagates() {
        // Audit is best-effort: a recorder failure must NEVER mask the real failure or turn a 502 into
        // a 500. The original ScrapeBlockedException must surface, not the recorder's RuntimeException.
        String reason = "cloudflare-managed:cf-ray=abc-TLV";
        when(extractionService.extractPrice(scrapeResponse)).thenThrow(new ScrapeBlockedException(reason));
        doThrow(new RuntimeException("recorder DB down"))
                .when(scrapeAttemptRecorder)
                .recordExtractionFailure(any(), any(), any(), any());

        assertThatThrownBy(() -> service.trackUrl(1L, new TrackRequest("https://example.com/item")))
                .isInstanceOf(ScrapeBlockedException.class)
                .hasMessageContaining(reason);
    }

    @Test
    void trackUrl_nullScrapeResponse_skipsSaveReturnsLastKnown() {
        PriceRecord previous = priceRecord("99.00", "USD");
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item))
                .thenReturn(Optional.of(previous));
        when(scraperClient.scrape(any())).thenReturn(null);

        TrackResponse response = service.trackUrl(1L, new TrackRequest("https://example.com/item"));

        verify(priceRecordRepository, never()).save(any());
        assertThat(response.currentPrice()).isEqualByComparingTo("99.00");
        // A null scrape has no evidence to replay — deliberately NOT recorded (#131).
        verifyNoInteractions(scrapeAttemptRecorder);
    }

    private PriceRecord priceRecord(String price, String currency) {
        return PriceRecord.builder()
                .price(new BigDecimal(price))
                .currency(currency)
                .availability(AvailabilityStatus.AVAILABLE)
                .extractionSource(ExtractionSource.STRUCTURED)
                .trackedItem(item)
                .build();
    }
}
