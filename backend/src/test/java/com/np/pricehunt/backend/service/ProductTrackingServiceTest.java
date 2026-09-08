package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.auth.CurrentUser;
import com.np.pricehunt.backend.config.PriceTrackingProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.dto.CreateProductRequest;
import com.np.pricehunt.backend.dto.CreateProductResponse;
import com.np.pricehunt.backend.dto.TrackRequest;
import com.np.pricehunt.backend.dto.TrackResponse;
import com.np.pricehunt.backend.exception.NotFoundException;
import com.np.pricehunt.backend.exception.RefreshCooldownException;
import com.np.pricehunt.backend.exception.ValidationException;
import com.np.pricehunt.backend.repository.projection.TrackedListingRef;
import com.np.pricehunt.backend.service.ratelimit.RefreshCooldownLimiter;
import com.np.pricehunt.backend.tenancy.UserScopedCatalog;
import com.np.pricehunt.backend.validator.UrlValidator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The user-facing orchestration (#246): membership is proven through the port before anything else
 * happens, the trackedProductCatalog half and the price check are delegated, and the cooldown rules of a user
 * refresh hold. The pipeline itself is covered by the {@code PriceCheckPipeline*Test} suites.
 */
@ExtendWith(MockitoExtension.class)
class ProductTrackingServiceTest {

    private static final PriceTrackingProperties TRACKING_PROPERTIES =
            new PriceTrackingProperties(200, Duration.ofMinutes(1), 20);
    private static final long USER_ID = 7L;
    private static final long LISTING_ID = 1L;
    private static final String URL = "https://example.com/item";
    private static final TrackResponse RESPONSE = new TrackResponse(
            1L, "P", 1L, URL, "example.com", null, null, null, AvailabilityStatus.UNKNOWN, null, null);

    @Mock
    private CurrentUser currentUser;

    @Mock
    private UserScopedCatalog trackedProductCatalog;

    @Mock
    private ProductCatalogService sharedCatalog;

    @Mock
    private PriceCheckPipeline pipeline;

    @Mock
    private UrlValidator urlValidator;

    @Mock
    private RefreshCooldownLimiter cooldownLimiter;

    @Mock
    private TransactionTemplate transactionTemplate;

    private ProductTrackingService service;

    @BeforeEach
    void setUp() {
        service = new ProductTrackingService(
                currentUser,
                trackedProductCatalog,
                sharedCatalog,
                pipeline,
                urlValidator,
                cooldownLimiter,
                TRACKING_PROPERTIES,
                transactionTemplate,
                Clock.systemUTC());
    }

    private void runCallbacksInline() {
        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
    }

    // --- createProduct ---

    @Test
    void createProduct_tracksItForTheCaller_inTheSameTransaction() {
        runCallbacksInline();
        when(currentUser.userId()).thenReturn(USER_ID);
        when(sharedCatalog.createProduct(any())).thenReturn(new CreateProductResponse(5L, "Sony"));

        CreateProductResponse created = service.createProduct(new CreateProductRequest("Sony"));

        assertThat(created.id()).isEqualTo(5L);
        var order = inOrder(sharedCatalog, trackedProductCatalog);
        order.verify(sharedCatalog).createProduct(any());
        order.verify(trackedProductCatalog).track(USER_ID, 5L);
        verify(transactionTemplate).execute(any());
    }

    @Test
    void createProduct_nullRequest_returns400_beforeResolvingTheCaller() {
        assertThatThrownBy(() -> service.createProduct(null)).isInstanceOf(ValidationException.class);

        verifyNoInteractions(currentUser, sharedCatalog, trackedProductCatalog);
    }

    // --- trackUrl ---

    @Test
    void trackUrl_admitsThenTracks_thenChecksThePrice() {
        runCallbacksInline();
        when(currentUser.userId()).thenReturn(USER_ID);
        when(sharedCatalog.productExists(1L)).thenReturn(true);
        when(sharedCatalog.admitListing(1L, URL)).thenReturn(LISTING_ID);
        when(pipeline.checkPrice(LISTING_ID, URL, false)).thenReturn(RESPONSE);

        TrackResponse response = service.trackUrl(1L, new TrackRequest(URL));

        assertThat(response).isSameAs(RESPONSE);
        // Validate → admit + track (one transaction) → check. The submitted URL was just validated,
        // so the pipeline is told not to resolve it again.
        var order = inOrder(urlValidator, sharedCatalog, trackedProductCatalog, pipeline);
        order.verify(urlValidator).validate(URL);
        order.verify(sharedCatalog).admitListing(1L, URL);
        order.verify(trackedProductCatalog).track(USER_ID, 1L);
        order.verify(pipeline).checkPrice(LISTING_ID, URL, false);
        verifyNoInteractions(cooldownLimiter);
    }

    @Test
    void trackUrl_nonexistentProduct_returns404_withoutValidating() {
        // The cheap existence check runs BEFORE DNS-based validation, so a bad product id 404s
        // without consuming a resolver slot — and before any membership is written.
        when(currentUser.userId()).thenReturn(USER_ID);
        when(sharedCatalog.productExists(999L)).thenReturn(false);

        assertThatThrownBy(() -> service.trackUrl(999L, new TrackRequest(URL))).isInstanceOf(NotFoundException.class);

        verify(urlValidator, never()).validate(any());
        verify(trackedProductCatalog, never()).track(anyLong(), anyLong());
        verifyNoInteractions(pipeline);
    }

    @Test
    void trackUrl_rejectedUrl_isNeverAdmittedOrTracked() {
        when(currentUser.userId()).thenReturn(USER_ID);
        when(sharedCatalog.productExists(1L)).thenReturn(true);
        doThrow(new ValidationException("URL host is not allowed"))
                .when(urlValidator)
                .validate(URL);

        assertThatThrownBy(() -> service.trackUrl(1L, new TrackRequest(URL))).isInstanceOf(ValidationException.class);

        verify(sharedCatalog, never()).admitListing(anyLong(), any());
        verify(trackedProductCatalog, never()).track(anyLong(), anyLong());
        verifyNoInteractions(pipeline, transactionTemplate);
    }

    @Test
    void trackUrl_nullRequest_returns400_withoutValidating() {
        assertThatThrownBy(() -> service.trackUrl(1L, null)).isInstanceOf(ValidationException.class);

        verifyNoInteractions(urlValidator, sharedCatalog, trackedProductCatalog, pipeline);
    }

    // --- refreshTrackedItem ---

    @Test
    void refreshTrackedItem_notTheCallers_throwsNotFound_beforeAnyCooldown() {
        // A foreign product, a foreign item or a missing one are the same empty answer from the port.
        when(currentUser.userId()).thenReturn(USER_ID);
        when(trackedProductCatalog.listing(USER_ID, 1L, 99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 99L)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(cooldownLimiter, pipeline);
    }

    @Test
    void refreshTrackedItem_recentlyRefreshed_throwsTooManyRequests() {
        when(currentUser.userId()).thenReturn(USER_ID);
        when(trackedProductCatalog.listing(USER_ID, 1L, 1L))
                .thenReturn(Optional.of(new TrackedListingRef(
                        1L, URL, "example.com", Instant.now().minusSeconds(10))));

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 1L)).isInstanceOf(RefreshCooldownException.class);

        // Durable (DB lastChecked) cooldown rejects before the volatile limiter is consulted.
        verifyNoInteractions(cooldownLimiter, pipeline);
    }

    @Test
    void refreshTrackedItem_volatileCooldownActive_throwsTooManyRequests() {
        // DB lastChecked is null (durable check passes), but the in-memory limiter rejects —
        // e.g. a rapid retry after a failed scrape. Must 429 before scraping.
        when(currentUser.userId()).thenReturn(USER_ID);
        when(trackedProductCatalog.listing(USER_ID, 1L, 1L))
                .thenReturn(Optional.of(new TrackedListingRef(1L, URL, "example.com", null)));
        when(cooldownLimiter.tryAcquire(1L)).thenReturn(false);

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 1L)).isInstanceOf(RefreshCooldownException.class);

        verifyNoInteractions(pipeline);
    }

    @Test
    void refreshTrackedItem_found_revalidatesTheStoredUrlThroughThePipeline() {
        when(currentUser.userId()).thenReturn(USER_ID);
        when(trackedProductCatalog.listing(USER_ID, 1L, 1L))
                .thenReturn(Optional.of(new TrackedListingRef(1L, URL, "example.com", null)));
        when(cooldownLimiter.tryAcquire(1L)).thenReturn(true);
        when(pipeline.checkPrice(LISTING_ID, URL, true)).thenReturn(RESPONSE);

        TrackResponse response = service.refreshTrackedItem(1L, 1L);

        assertThat(response).isSameAs(RESPONSE);
        verify(pipeline).checkPrice(LISTING_ID, URL, true);
    }

    @Test
    void refreshTrackedItem_rejection_stillConsumesCooldown() {
        // Intentional ordering: the volatile cooldown is consumed BEFORE the pipeline's chokepoint
        // check, so a blocked refresh still burns the window (locks the ordering against a reorder).
        when(currentUser.userId()).thenReturn(USER_ID);
        when(trackedProductCatalog.listing(USER_ID, 1L, 1L))
                .thenReturn(Optional.of(new TrackedListingRef(1L, URL, "example.com", null)));
        when(cooldownLimiter.tryAcquire(1L)).thenReturn(true);
        when(pipeline.checkPrice(LISTING_ID, URL, true)).thenThrow(new ValidationException("URL host is not allowed"));

        assertThatThrownBy(() -> service.refreshTrackedItem(1L, 1L)).isInstanceOf(ValidationException.class);

        verify(cooldownLimiter).tryAcquire(1L);
    }

    // --- stopTracking ---

    @Test
    void stopTracking_deletesOnlyTheCallersMembership() {
        when(currentUser.userId()).thenReturn(USER_ID);
        when(trackedProductCatalog.stopTracking(USER_ID, 3L)).thenReturn(true);

        service.stopTracking(3L);

        verify(trackedProductCatalog).stopTracking(USER_ID, 3L);
        verifyNoInteractions(sharedCatalog);
    }

    @Test
    void stopTracking_neverTracked_throwsNotFound() {
        when(currentUser.userId()).thenReturn(USER_ID);
        when(trackedProductCatalog.stopTracking(USER_ID, 3L)).thenReturn(false);

        assertThatThrownBy(() -> service.stopTracking(3L)).isInstanceOf(NotFoundException.class);
    }
}
