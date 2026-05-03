package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.client.ScraperClient;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.*;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import org.junit.jupiter.api.BeforeEach;X
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductTrackingServiceValidationTest {

    @Mock private ProductRepository productRepository;
    @Mock private TrackedItemRepository trackedItemRepository;
    @Mock private PriceRecordRepository priceRecordRepository;
    @Mock private PriceExtractionService extractionService;
    @Mock private ScraperClient scraperClient;

    @InjectMocks
    private ProductTrackingService service;

    private Product product;
    private TrackedItem item;
    private ScrapeResponse scrapeResponse;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "maxDeltaPercent", 200);

        product = Product.builder().id(1L).name("Test Product").build();
        item = TrackedItem.builder().id(1L).url("https://example.com/item").shopName("example.com").product(product).build();
        scrapeResponse = new ScrapeResponse(ExtractionSource.STRUCTURED,
                new ScrapeResponse.PriceData(new BigDecimal("100.00"), "USD", true), null, null);

        when(productRepository.findById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findByUrl(any())).thenReturn(Optional.of(item));
        when(scraperClient.scrape(any())).thenReturn(scrapeResponse);
    }

    @Test
    void trackUrl_validPrice_noHistory_savesPriceRecord() {
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item)).thenReturn(Optional.empty());
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(new BigDecimal("100.00"), "USD", true, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "timestamp", LocalDateTime.now());
            return r;
        });

        TrackResponse response = service.trackUrl(1L, new TrackRequest("https://example.com/item", null));

        assertThat(response.currentPrice()).isEqualByComparingTo("100.00");
        verify(priceRecordRepository).save(any());
    }

    @Test
    void trackUrl_zeroPricce_skipsave() {
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item)).thenReturn(Optional.empty());
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(BigDecimal.ZERO, "USD", true, ExtractionSource.FULLTEXT));

        service.trackUrl(1L, new TrackRequest("https://example.com/item", null));

        verify(priceRecordRepository, never()).save(any());
    }

    @Test
    void trackUrl_negativePricerice_skipsSave() {
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item)).thenReturn(Optional.empty());
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(new BigDecimal("-5.00"), "USD", true, ExtractionSource.FULLTEXT));

        service.trackUrl(1L, new TrackRequest("https://example.com/item", null));

        verify(priceRecordRepository, never()).save(any());
    }

    @Test
    void trackUrl_nullCurrency_skipsSave() {
        PriceRecord previous = priceRecord("100.00", "USD");
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item)).thenReturn(Optional.of(previous));
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(new BigDecimal("105.00"), null, true, ExtractionSource.FULLTEXT));

        service.trackUrl(1L, new TrackRequest("https://example.com/item", null));

        verify(priceRecordRepository, never()).save(any());
    }

    @Test
    void trackUrl_currencyChanged_skipsDeltaCheckAndSaves() {
        PriceRecord previous = priceRecord("100.00", "USD");
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item)).thenReturn(Optional.of(previous));
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(new BigDecimal("90.00"), "EUR", true, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "timestamp", LocalDateTime.now());
            return r;
        });

        TrackResponse response = service.trackUrl(1L, new TrackRequest("https://example.com/item", null));

        verify(priceRecordRepository).save(any());
        assertThat(response.currency()).isEqualTo("EUR");
    }

    @Test
    void trackUrl_priceWithinDelta_saves() {
        PriceRecord previous = priceRecord("100.00", "USD");
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item)).thenReturn(Optional.of(previous));
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(new BigDecimal("250.00"), "USD", true, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "timestamp", LocalDateTime.now());
            return r;
        });

        service.trackUrl(1L, new TrackRequest("https://example.com/item", null));

        verify(priceRecordRepository).save(any());
    }

    @Test
    void trackUrl_priceExceedsDelta_skipsSave() {
        PriceRecord previous = priceRecord("100.00", "USD");
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item)).thenReturn(Optional.of(previous));
        // 400 is 4x previous, exceeds 200% delta (max is 3x)
        when(extractionService.extractPrice(scrapeResponse))
                .thenReturn(new PriceInfo(new BigDecimal("400.00"), "USD", true, ExtractionSource.FULLTEXT));

        service.trackUrl(1L, new TrackRequest("https://example.com/item", null));

        verify(priceRecordRepository, never()).save(any());
    }

    @Test
    void trackUrl_nullScrapeResponse_skipsSaveReturnsLastKnown() {
        PriceRecord previous = priceRecord("99.00", "USD");
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item)).thenReturn(Optional.of(previous));
        when(scraperClient.scrape(any())).thenReturn(null);

        TrackResponse response = service.trackUrl(1L, new TrackRequest("https://example.com/item", null));

        verify(priceRecordRepository, never()).save(any());
        assertThat(response.currentPrice()).isEqualByComparingTo("99.00");
    }

    private PriceRecord priceRecord(String price, String currency) {
        return PriceRecord.builder()
                .price(new BigDecimal(price))
                .currency(currency)
                .available(true)
                .extractionSource(ExtractionSource.STRUCTURED)
                .trackedItem(item)
                .build();
    }
}
