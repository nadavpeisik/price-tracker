package com.np.pricehunt.backend.tenancy;

import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.UserProductRepository;
import com.np.pricehunt.backend.repository.projection.DashboardListingRef;
import com.np.pricehunt.backend.repository.projection.ListingLatestObservationRow;
import com.np.pricehunt.backend.repository.projection.TrackedListingRef;
import com.np.pricehunt.backend.repository.projection.TrackedProductDetailRef;
import com.np.pricehunt.backend.repository.projection.TrackedProductRef;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The catalog as one user is allowed to see and change it (issue #246). This is the only data access
 * a user-facing service has: every method takes the caller's internal id first, and membership is
 * proven inside the query rather than by a lookup followed by an ownership check, so a foreign or
 * missing row is the same empty answer — which the service turns into a 404, never a 403.
 *
 * <p>A class, not a repository, on purpose: a {@code JpaRepository} carries {@code findAll},
 * {@code findById} and {@code deleteById} whether or not anyone means to expose them, and the
 * boundary this exists for is that nothing holding a {@code CurrentUser} can reach an unscoped query.
 * A question with no {@code userId} to take does not belong here however convenient it looks: "does
 * this product exist at all", which the track flow legitimately asks, lives on {@code
 * ProductCatalogService}.
 */
@Component
@RequiredArgsConstructor
public class UserScopedCatalog {

    private final UserProductRepository userProducts;
    private final PriceRecordRepository priceRecords;
    private final Clock clock;

    /** A listing the caller may read, with its history in the requested window. */
    public record ListingHistory(TrackedListingRef listing, List<PriceRecord> records) {}

    @Transactional(readOnly = true)
    public List<TrackedProductRef> trackedProducts(long userId) {
        return userProducts.findTrackedProducts(userId);
    }

    /** Every listing under every product the caller tracks — the dashboard's whole-set input. */
    @Transactional(readOnly = true)
    public List<DashboardListingRef> listingsOfTrackedProducts(long userId) {
        return userProducts.findListingsOfTrackedProducts(userId);
    }

    @Transactional(readOnly = true)
    public Optional<TrackedProductDetailRef> trackedProduct(long userId, long productId) {
        return userProducts.findTrackedProduct(userId, productId);
    }

    @Transactional(readOnly = true)
    public List<DashboardListingRef> listings(long userId, long productId) {
        return userProducts.findListings(userId, productId);
    }

    @Transactional(readOnly = true)
    public Optional<TrackedListingRef> listing(long userId, long productId, long itemId) {
        return userProducts.findListing(userId, productId, itemId);
    }

    /**
     * Empty both when the product is not the caller's and when it has no listings; resolve the product
     * first ({@link #product}) so the two are told apart.
     */
    @Transactional(readOnly = true)
    public List<ListingLatestObservationRow> listingsWithLatestObservation(long userId, long productId, Instant asOf) {
        return userProducts.findListingsWithLatestObservation(userId, productId, asOf);
    }

    /**
     * Membership is proven once, by {@link #listing}; the records are then read by id. Two statements
     * rather than one ad-hoc entity join, because this class is the trust boundary and proving inside it
     * is enough.
     */
    @Transactional(readOnly = true)
    public Optional<ListingHistory> priceHistory(long userId, long productId, long itemId, Instant from, Instant to) {
        return listing(userId, productId, itemId)
                .map(ref -> new ListingHistory(
                        ref,
                        priceRecords.findByTrackedItemIdAndObservedAtBetweenOrderByObservedAtDesc(ref.id(), from, to)));
    }

    /** Idempotent: a re-track keeps the original {@code added_at}. Joins the caller's transaction. */
    @Transactional
    public void track(long userId, long productId) {
        userProducts.track(userId, productId, clock.instant());
    }

    /** @return false when the caller never tracked the product — the service's 404 */
    @Transactional
    public boolean stopTracking(long userId, long productId) {
        return userProducts.stopTracking(userId, productId) > 0;
    }
}
