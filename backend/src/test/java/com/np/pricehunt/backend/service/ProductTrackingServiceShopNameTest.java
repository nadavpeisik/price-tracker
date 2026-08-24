package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.np.pricehunt.backend.client.ScraperClient;
import com.np.pricehunt.backend.config.PriceTrackingProperties;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import com.np.pricehunt.backend.dto.TrackRequest;
import com.np.pricehunt.backend.observability.ScrapeAttemptRecorder;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
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
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

// How the price-check pipeline drives ShopNameAssignment: URL name before the scrape, page name after
// it unless the URL name was curated, and name-before-price ordering. The assignment's own choreography
// is covered by ShopNameAssignmentTest.
@ExtendWith(MockitoExtension.class)
class ProductTrackingServiceShopNameTest {

    private static final PriceTrackingProperties TRACKING_PROPERTIES =
            new PriceTrackingProperties(200, Duration.ofMinutes(1), 20);

    private static final String URL = "https://thomannmusic.com/x.htm";

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

        Product product = Product.builder().id(1L).name("P").build();
        item = TrackedItem.builder().id(1L).url(URL).product(product).build();

        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        when(productRepository.existsById(1L)).thenReturn(true);
        when(productRepository.findForUpdateById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findByUrl(any())).thenReturn(Optional.of(item));
    }

    // The persist step reads the latest price; "no history" means no PriceRecord is saved.
    private void stubPersistReadsEmpty() {
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(any()))
                .thenReturn(Optional.empty());
    }

    private static ScrapeResponse scrapeWithProposal(String name, boolean strong) {
        return new ScrapeResponse(
                ExtractionSource.SNIPPET,
                null,
                "snippet",
                null,
                null,
                new ScrapeResponse.ShopNameProposal(name, strong));
    }

    @Test
    void urlNameBeforeTheScrape_pageNameAfterIt() {
        ScrapeResponse scraped = scrapeWithProposal("Musikhaus Thomann", true);
        when(shopNameAssignment.applyNameFromUrl(1L, URL)).thenReturn(false);
        when(scraperClient.scrape(URL)).thenReturn(scraped);
        when(extractionService.extractPrice(any())).thenReturn(null);
        stubPersistReadsEmpty();

        service.trackUrl(1L, new TrackRequest(URL));

        var order = inOrder(shopNameAssignment, scraperClient);
        order.verify(shopNameAssignment).applyNameFromUrl(1L, URL);
        order.verify(scraperClient).scrape(URL);
        order.verify(shopNameAssignment).applyNameFromPage(1L, URL, scraped.shopNameProposal());
    }

    @Test
    void curatedUrlName_wins_pageIsNeverConsulted() {
        // Even with a strong proposal on the page, a curated mapping is final.
        when(shopNameAssignment.applyNameFromUrl(1L, URL)).thenReturn(true);
        when(scraperClient.scrape(URL)).thenReturn(scrapeWithProposal("Strong Site", true));
        when(extractionService.extractPrice(any())).thenReturn(null);
        stubPersistReadsEmpty();

        service.trackUrl(1L, new TrackRequest(URL));

        verify(shopNameAssignment, never()).applyNameFromPage(any(), any(), any());
    }

    @Test
    void nullScrape_skipsThePageName_keepsTheUrlName() {
        when(shopNameAssignment.applyNameFromUrl(1L, URL)).thenReturn(false);
        when(scraperClient.scrape(URL)).thenReturn(null);
        stubPersistReadsEmpty();

        service.trackUrl(1L, new TrackRequest(URL));

        verify(shopNameAssignment).applyNameFromUrl(1L, URL);
        verify(shopNameAssignment, never()).applyNameFromPage(any(), any(), any());
    }

    @Test
    void nameIsAssignedBeforePriceExtractionFails() {
        when(shopNameAssignment.applyNameFromUrl(1L, URL)).thenReturn(false);
        when(scraperClient.scrape(URL)).thenReturn(scrapeWithProposal("Musikhaus Thomann", true));
        when(extractionService.extractPrice(any())).thenThrow(new RuntimeException("LLM blew up"));

        assertThatThrownBy(() -> service.trackUrl(1L, new TrackRequest(URL))).isInstanceOf(RuntimeException.class);

        // Both name steps ran before extraction threw — a price failure never loses the name.
        verify(shopNameAssignment).applyNameFromUrl(1L, URL);
        verify(shopNameAssignment).applyNameFromPage(eq(1L), eq(URL), any());
    }
}
