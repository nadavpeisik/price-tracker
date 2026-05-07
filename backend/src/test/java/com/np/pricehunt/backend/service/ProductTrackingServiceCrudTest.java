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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProductTrackingServiceCrudTest {

    @Mock private ProductRepository productRepository;
    @Mock private TrackedItemRepository trackedItemRepository;
    @Mock private PriceRecordRepository priceRecordRepository;
    @Mock private PriceExtractionService extractionService;
    @Mock private ScraperClient scraperClient;
    @Mock private TransactionTemplate transactionTemplate;

    @InjectMocks private ProductTrackingService service;

    private Product product;
    private TrackedItem item;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "maxDeltaPercent", 200);
        ReflectionTestUtils.setField(service, "minRefreshIntervalSeconds", 60);
        // Run transactionTemplate callbacks inline so phase splits are exercised end-to-end.
        lenient().when(transactionTemplate.execute(any())).thenAnswer(inv -> {
            TransactionCallback<?> cb = inv.getArgument(0);
            return cb.doInTransaction(null);
        });
        product = Product.builder().id(1L).name("Laptop").build();
        item = TrackedItem.builder().id(1L).url("https://amazon.com/dp/1").shopName("amazon.com").product(product).build();
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
        TrackedItem foreignItem = TrackedItem.builder().id(1L).url("http://x.com").shopName("x").product(other).build();
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
        TrackedItem recentItem = TrackedItem.builder().id(1L).url("https://amazon.com/dp/1")
                .shopName("amazon.com").product(product)
                .lastChecked(LocalDateTime.now().minusSeconds(10))
                .build();
        ReflectionTestUtils.setField(service, "minRefreshIntervalSeconds", 60);
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(recentItem));

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
    }

    @Test
    void refreshTrackedItem_wrongProduct_throwsException() {
        Product other = Product.builder().id(99L).name("Other").build();
        TrackedItem foreignItem = TrackedItem.builder().id(1L).url("http://x.com").shopName("x").product(other).build();
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(foreignItem));

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void refreshTrackedItem_found_callsScraper() {
        ScrapeResponse scraped = new ScrapeResponse(ExtractionSource.STRUCTURED,
                new ScrapeResponse.PriceData(new BigDecimal("899.99"), "USD", true), null, null);
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item)).thenReturn(Optional.empty());
        when(scraperClient.scrape(item.getUrl())).thenReturn(scraped);
        when(extractionService.extractPrice(scraped))
                .thenReturn(new PriceInfo(new BigDecimal("899.99"), "USD", true, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "timestamp", LocalDateTime.now());
            return r;
        });

        TrackResponse response = service.refreshTrackedItem(1L, 1L);

        verify(scraperClient).scrape(item.getUrl());
        assertThat(response.currentPrice()).isEqualByComparingTo("899.99");
    }

    @Test
    void refreshTrackedItem_secondAttemptAfterFailedScrape_throwsTooManyRequests() {
        // First attempt: scraper returns null. lastChecked never bumped on the entity, but the
        // in-memory rate-limiter is stamped during Phase 1. Second attempt must hit the cap.
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item)).thenReturn(Optional.empty());
        when(scraperClient.scrape(item.getUrl())).thenReturn(null);

        TrackResponse first = service.refreshTrackedItem(1L, 1L);
        assertThat(first.currentPrice()).isNull();

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 1L))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.TOO_MANY_REQUESTS);

        verify(scraperClient, times(1)).scrape(item.getUrl());
    }

    @Test
    void scrapeAndPersist_normalizesCurrencyToUppercase() {
        ScrapeResponse scraped = new ScrapeResponse(ExtractionSource.STRUCTURED,
                new ScrapeResponse.PriceData(new BigDecimal("100.00"), "usd", true), null, null);
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item)).thenReturn(Optional.empty());
        when(scraperClient.scrape(item.getUrl())).thenReturn(scraped);
        when(extractionService.extractPrice(scraped))
                .thenReturn(new PriceInfo(new BigDecimal("100.00"), "usd", true, ExtractionSource.STRUCTURED));
        when(priceRecordRepository.save(any())).thenAnswer(inv -> {
            PriceRecord r = inv.getArgument(0);
            ReflectionTestUtils.setField(r, "timestamp", LocalDateTime.now());
            return r;
        });

        service.refreshTrackedItem(1L, 1L);

        org.mockito.ArgumentCaptor<PriceRecord> captor = org.mockito.ArgumentCaptor.forClass(PriceRecord.class);
        verify(priceRecordRepository).save(captor.capture());
        assertThat(captor.getValue().getCurrency()).isEqualTo("USD");
    }
}
