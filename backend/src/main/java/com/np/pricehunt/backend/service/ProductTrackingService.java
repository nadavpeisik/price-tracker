package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.auth.CurrentUser;
import com.np.pricehunt.backend.config.PriceTrackingProperties;
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
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a signed-in user does to their tracked set (#246): create a product and track it, track a URL,
 * refresh one of their listings, stop tracking a product. Each action is "prove membership through
 * the tenancy port, then hand the catalog half to {@link ProductCatalogService} or the price check to
 * {@link PriceCheckPipeline}" — this class holds a {@code CurrentUser} and therefore no repository.
 *
 * <p>Tracking a URL is the one deliberately catalog-global entry: attaching to a product someone else
 * created is the shared-catalog feature. Admission and membership commit in one transaction, under
 * the product write lock and before any network I/O, so a failed first scrape never undoes "I want to
 * track this".
 */
@Service
@RequiredArgsConstructor
public class ProductTrackingService {

    private final CurrentUser currentUser;
    private final UserScopedCatalog trackedProductCatalog;
    private final ProductCatalogService sharedCatalog;
    private final PriceCheckPipeline pipeline;
    private final UrlValidator urlValidator;
    private final RefreshCooldownLimiter cooldownLimiter;
    private final PriceTrackingProperties trackingProperties;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    /** Creating a product is tracking it: without the membership the creator would 404 on their own row. */
    public CreateProductResponse createProduct(CreateProductRequest request) {
        // A literal JSON `null` body reaches here; an empty body is already a 400 upstream.
        if (request == null) {
            throw new ValidationException("Request body is required");
        }
        long userId = currentUser.userId();
        return transactionTemplate.execute(status -> {
            CreateProductResponse created = sharedCatalog.createProduct(request);
            trackedProductCatalog.track(userId, created.id());
            return created;
        });
    }

    public TrackResponse trackUrl(Long productId, TrackRequest request) {
        // A literal JSON `null` body reaches here; an empty body is already a 400 upstream.
        if (request == null) {
            throw new ValidationException("Request body is required");
        }
        long userId = currentUser.userId();
        // Cheap 404 before the DNS-based validation; admission re-checks under the product lock.
        if (!sharedCatalog.productExists(productId)) {
            throw new NotFoundException("Product not found");
        }
        // Validate before admission so a rejected URL is never persisted.
        urlValidator.validate(request.url());
        // One transaction: the listing joins the catalog and the caller joins the product together,
        // under the product write lock, released before any network I/O.
        Long listingId = transactionTemplate.execute(status -> {
            Long admitted = sharedCatalog.admitListing(productId, request.url());
            trackedProductCatalog.track(userId, productId);
            return admitted;
        });

        // The submitted URL was just validated; avoid a second DNS resolution.
        return pipeline.checkPrice(listingId, request.url(), false);
    }

    public TrackResponse refreshTrackedItem(Long productId, Long itemId) {
        TrackedListingRef listing = trackedProductCatalog
                .listing(currentUser.userId(), productId, itemId)
                .orElseThrow(() -> new NotFoundException("Tracked item not found"));
        enforcePersistedRefreshCooldown(listing.lastChecked());

        // Acquired before scraping, outside any transaction; a failed scrape still consumes the cooldown.
        if (!cooldownLimiter.tryAcquire(listing.id())) {
            throw new RefreshCooldownException("Item was refreshed recently, try again later");
        }

        // Cooldown is consumed before the stored URL is validated, so a rejected refresh burns the window too.
        return pipeline.checkPrice(listing.id(), listing.url(), true);
    }

    /** Deletes the caller's membership and nothing else; the catalog row stays for everyone else. */
    public void stopTracking(Long productId) {
        if (!trackedProductCatalog.stopTracking(currentUser.userId(), productId)) {
            throw new NotFoundException("Product not found");
        }
    }

    // `lastChecked` is the restart-safe half of the cooldown; failed attempts are covered by cooldownLimiter.
    private void enforcePersistedRefreshCooldown(Instant persistedLastChecked) {
        Instant cutoff = clock.instant().minus(trackingProperties.minRefreshInterval());
        if (persistedLastChecked != null && persistedLastChecked.isAfter(cutoff)) {
            throw new RefreshCooldownException("Item was refreshed recently, try again later");
        }
    }
}
