package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.client.ScraperClient;
import com.np.pricehunt.backend.config.PriceTrackingProperties;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.MappingOrigin;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.ShopNameSource;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import com.np.pricehunt.backend.dto.TrackRequest;
import com.np.pricehunt.backend.observability.ScrapeAttemptRecorder;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.service.ShopNameResolver.Resolved;
import com.np.pricehunt.backend.service.ratelimit.RefreshCooldownLimiter;
import com.np.pricehunt.backend.validator.UrlValidator;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

// Orchestration of the shop-name lifecycle inside trackAndPersist: curated short-circuit, the
// strong-only learn gate, and name-before-price ordering. DB semantics of resolve/apply/upsert are
// covered by ShopNameResolverTest / TrackedItemRepositoryTest / ShopNameMappingRepositoryTest.
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
    private ShopNameResolver shopNameResolver;

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
                shopNameResolver,
                cooldownLimiter,
                Clock.systemUTC(),
                scrapeAttemptRecorder,
                new PriceValidator(TRACKING_PROPERTIES));

        Product product = Product.builder().id(1L).name("P").build();
        item = TrackedItem.builder().id(1L).url(URL).product(product).build();

        // Universal: every test runs the gating tx + the lifecycle's execute-based steps.
        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
        when(productRepository.existsById(1L)).thenReturn(true);
        when(productRepository.findForUpdateById(1L)).thenReturn(Optional.of(product));
        when(trackedItemRepository.findByUrl(any())).thenReturn(Optional.of(item));
    }

    // Make the step-3 name tx (transactionTemplate.executeWithoutResult) run inline.
    @SuppressWarnings("unchecked")
    private void runNameTxInline() {
        doAnswer(inv -> {
                    ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null);
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
    }

    // Step 5 reads the latest price; return "no history" so no PriceRecord is saved.
    private void stubPersistReadsEmpty() {
        when(trackedItemRepository.findById(1L)).thenReturn(Optional.of(item));
        when(priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(any()))
                .thenReturn(Optional.empty());
    }

    private ScrapeResponse scrapeWithProposal(String name, boolean strong) {
        return new ScrapeResponse(
                ExtractionSource.SNIPPET,
                null,
                "snippet",
                null,
                null,
                new ScrapeResponse.ShopNameProposal(name, strong));
    }

    @Test
    void curatedDomain_shortCircuits_noLearnNoPostScrapeResolve() {
        // Even with a strong proposal on the page, a curated pre-scrape lock skips all post-scrape
        // name work: learn is never called and resolve runs only once (step 1).
        when(shopNameResolver.resolve(any(), any()))
                .thenReturn(new Resolved("Amazon", ShopNameSource.MAPPING, MappingOrigin.CURATED));
        when(scraperClient.scrape(any())).thenReturn(scrapeWithProposal("Strong Site", true));
        when(extractionService.extractPrice(any())).thenReturn(null);
        stubPersistReadsEmpty();

        service.trackUrl(1L, new TrackRequest(URL));

        verify(shopNameResolver, never()).learn(any(), any());
        verify(shopNameResolver, times(1)).resolve(any(), any());
    }

    @Test
    void strongProposal_isLearned() {
        runNameTxInline();
        when(shopNameResolver.resolve(any(), any()))
                .thenReturn(new Resolved("thomannmusic.com", ShopNameSource.HOST_FALLBACK, null));
        when(scraperClient.scrape(any())).thenReturn(scrapeWithProposal("Musikhaus Thomann", true));
        when(extractionService.extractPrice(any())).thenReturn(null);
        stubPersistReadsEmpty();

        service.trackUrl(1L, new TrackRequest(URL));

        verify(shopNameResolver).learn(eq(URL), eq("Musikhaus Thomann"));
    }

    @Test
    void weakProposal_isNotLearned_butResolved() {
        runNameTxInline();
        when(shopNameResolver.resolve(any(), any()))
                .thenReturn(new Resolved("Some Title", ShopNameSource.DETECTED, null));
        when(scraperClient.scrape(any())).thenReturn(scrapeWithProposal("Some Title", false));
        when(extractionService.extractPrice(any())).thenReturn(null);
        stubPersistReadsEmpty();

        service.trackUrl(1L, new TrackRequest(URL));

        verify(shopNameResolver, never()).learn(any(), any());
        // step 1 + step 3 both resolve.
        verify(shopNameResolver, times(2)).resolve(any(), any());
    }

    @Test
    void nameIsAppliedBeforePriceExtractionFails() {
        runNameTxInline();
        when(shopNameResolver.resolve(any(), any()))
                .thenReturn(new Resolved("thomannmusic.com", ShopNameSource.HOST_FALLBACK, null));
        when(scraperClient.scrape(any())).thenReturn(scrapeWithProposal("Musikhaus Thomann", true));
        when(extractionService.extractPrice(any())).thenThrow(new RuntimeException("LLM blew up"));

        assertThatThrownBy(() -> service.trackUrl(1L, new TrackRequest(URL))).isInstanceOf(RuntimeException.class);

        // Name was committed (steps 1 and 3 applied it) before the price extraction threw.
        verify(trackedItemRepository, atLeastOnce()).applyShopName(eq(1L), any(), any());
    }

    @Test
    void nameResolutionFailure_doesNotBlockPriceTracking() {
        // Both name transactions are best-effort: a resolver/DB failure must never abort the track.
        runNameTxInline();
        when(shopNameResolver.resolve(any(), any())).thenThrow(new RuntimeException("name DB down"));
        when(scraperClient.scrape(any())).thenReturn(scrapeWithProposal("Some Title", false));
        when(extractionService.extractPrice(any())).thenReturn(null);
        stubPersistReadsEmpty();

        service.trackUrl(1L, new TrackRequest(URL)); // must not throw

        verify(scraperClient).scrape(any()); // got past the failed step-1 name floor
        verify(extractionService).extractPrice(any()); // and past the failed step-3 name tx
    }
}
