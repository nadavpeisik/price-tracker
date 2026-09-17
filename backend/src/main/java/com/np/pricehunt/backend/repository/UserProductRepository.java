package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.UserProduct;
import com.np.pricehunt.backend.repository.projection.DashboardListingRef;
import com.np.pricehunt.backend.repository.projection.ListingLatestObservationRow;
import com.np.pricehunt.backend.repository.projection.TrackedListingRef;
import com.np.pricehunt.backend.repository.projection.TrackedProductDetailRef;
import com.np.pricehunt.backend.repository.projection.TrackedProductRef;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * The membership-proving queries (issue #246). Every finder starts from {@code UserProduct} and walks
 * outward, so a row comes back only when the caller tracks the product it belongs to — "not yours"
 * and "does not exist" are the same empty result, which is what lets the service layer answer 404 for
 * both without a second lookup.
 */
@Repository
public interface UserProductRepository extends JpaRepository<UserProduct, Long> {

    @Query(
            """
            SELECT new com.np.pricehunt.backend.repository.projection.TrackedProductRef(p.id, p.name)
            FROM UserProduct up JOIN up.product p
            WHERE up.user.id = :userId
            ORDER BY p.id ASC
            """)
    List<TrackedProductRef> findTrackedProducts(@Param("userId") long userId);

    /**
     * The dashboard's listing feed: every shop under every product the caller tracks, minus the ones
     * they hid (#250). The outer join on {@code HiddenListing} with {@code h.id IS NULL} is the whole
     * hide semantics for rollups, facets and trends — nothing downstream needs to know a hide exists.
     */
    @Query(
            """
            SELECT new com.np.pricehunt.backend.repository.projection.DashboardListingRef(t.id, p.id, t.shopName)
            FROM UserProduct up JOIN up.product p JOIN p.trackedItems t
            LEFT JOIN HiddenListing h ON h.userProduct = up AND h.trackedItem = t
            WHERE up.user.id = :userId AND h.id IS NULL
            ORDER BY p.id ASC, t.id ASC
            """)
    List<DashboardListingRef> findListingsOfTrackedProducts(@Param("userId") long userId);

    // No join to trackedItems: one row per membership, and a product with zero listings must resolve.
    @Query(
            """
            SELECT new com.np.pricehunt.backend.repository.projection.TrackedProductDetailRef(p.id, p.name, p.description)
            FROM UserProduct up JOIN up.product p
            WHERE up.user.id = :userId AND p.id = :productId
            """)
    Optional<TrackedProductDetailRef> findTrackedProduct(
            @Param("userId") long userId, @Param("productId") long productId);

    /** One product's visible listings — the price-trend feed; hidden ones excluded as on the dashboard. */
    @Query(
            """
            SELECT new com.np.pricehunt.backend.repository.projection.DashboardListingRef(t.id, p.id, t.shopName)
            FROM UserProduct up JOIN up.product p JOIN p.trackedItems t
            LEFT JOIN HiddenListing h ON h.userProduct = up AND h.trackedItem = t
            WHERE up.user.id = :userId AND p.id = :productId AND h.id IS NULL
            ORDER BY t.id ASC
            """)
    List<DashboardListingRef> findListings(@Param("userId") long userId, @Param("productId") long productId);

    @Query(
            """
            SELECT new com.np.pricehunt.backend.repository.projection.TrackedListingRef(t.id, t.url, t.shopName, t.lastChecked)
            FROM UserProduct up JOIN up.product p JOIN p.trackedItems t
            WHERE up.user.id = :userId AND p.id = :productId AND t.id = :itemId
            """)
    Optional<TrackedListingRef> findListing(
            @Param("userId") long userId, @Param("productId") long productId, @Param("itemId") long itemId);

    /**
     * A tracked product's listings, each with its latest observation at or before {@code asOf} — one
     * statement regardless of listing count (issue #157), now joined through membership (#246).
     *
     * <p>{@code LEFT JOIN LATERAL … LIMIT 1} rather than a {@code ROW_NUMBER()} window: the lateral
     * subquery walks the {@code (tracked_item_id, observed_at)} index and stops after one row per
     * listing. The selection rule — latest at or before the instant, ties broken by higher record id —
     * is the dashboard's ({@code PriceRecordRepository.findCutoffObservations}), so the panel and the
     * row can never pick different observations for the same listing. Outer join on the observation
     * on purpose: a listing with no qualifying record is still a listing. Inner join on membership on
     * purpose: a foreign product yields nothing, and the caller resolves the product first so that
     * "nothing" is a 404 rather than an empty 200. Hidden listings are <em>included</em>, flagged
     * (#250): this is the feed the panel unhides from, so it must be able to see them.
     *
     * <p>Aliases are quoted because the interface projection binds by exact column label and Postgres
     * folds unquoted identifiers to lowercase.
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT t.id                  AS "trackedItemId",
                           t.url                 AS "url",
                           t.shop_name           AS "shopName",
                           t.shop_name_source    AS "shopNameSource",
                           t.last_checked        AS "lastChecked",
                           o.price               AS "price",
                           o.currency            AS "currency",
                           o.availability_status AS "availability",
                           o.observed_at         AS "observedAt",
                           (h.id IS NOT NULL)    AS "hidden"
                    FROM tracked_item t
                    JOIN user_product up ON up.product_id = t.product_id AND up.user_id = :userId
                    LEFT JOIN hidden_listing h ON h.user_product_id = up.id AND h.tracked_item_id = t.id
                    LEFT JOIN LATERAL (
                        SELECT r.price, r.currency, r.availability_status, r.observed_at
                        FROM price_record r
                        WHERE r.tracked_item_id = t.id AND r.observed_at <= :asOf
                        ORDER BY r.observed_at DESC, r.id DESC
                        LIMIT 1
                    ) o ON true
                    WHERE t.product_id = :productId
                    ORDER BY t.id ASC
                    """)
    List<ListingLatestObservationRow> findListingsWithLatestObservation(
            @Param("userId") long userId, @Param("productId") long productId, @Param("asOf") Instant asOf);

    /**
     * Records membership, idempotently. {@code ON CONFLICT DO NOTHING} rather than exists-then-save:
     * it is one statement, it cannot race itself, and it leaves {@code added_at} alone on a re-track,
     * so "when I started tracking this" survives tracking a second URL under the same product.
     *
     * @return 1 when a row was created, 0 when the membership already existed
     */
    @Modifying
    @Query(
            nativeQuery = true,
            value =
                    """
                    INSERT INTO user_product (user_id, product_id, added_at)
                    VALUES (:userId, :productId, :now)
                    ON CONFLICT (user_id, product_id) DO NOTHING
                    """)
    int track(@Param("userId") long userId, @Param("productId") long productId, @Param("now") Instant now);

    /** @return rows deleted — 0 means the caller never tracked the product (or it does not exist) */
    @Modifying
    @Query("DELETE FROM UserProduct up WHERE up.user.id = :userId AND up.product.id = :productId")
    int stopTracking(@Param("userId") long userId, @Param("productId") long productId);

    /**
     * Hides one listing for the caller's membership, idempotently (#250). The membership row is
     * resolved inside the statement, so a caller who does not track the product inserts nothing;
     * that the listing belongs to the product is the caller's job ({@code findListing} first), which
     * is why the statement carries no join to {@code tracked_item}.
     *
     * @return 1 when a row was created, 0 when it already existed or the caller is not a member
     */
    @Modifying
    @Query(
            nativeQuery = true,
            value =
                    """
                    INSERT INTO hidden_listing (user_product_id, tracked_item_id)
                    SELECT up.id, :itemId FROM user_product up
                    WHERE up.user_id = :userId AND up.product_id = :productId
                    ON CONFLICT (user_product_id, tracked_item_id) DO NOTHING
                    """)
    int hideListing(@Param("userId") long userId, @Param("productId") long productId, @Param("itemId") long itemId);

    /**
     * Shows one listing again for the caller's membership only — another user's hide of the same
     * shared listing hangs off their own membership row and is untouched. Idempotent: a visible
     * listing deletes nothing.
     *
     * @return rows deleted (0 or 1)
     */
    @Modifying
    @Query(
            nativeQuery = true,
            value =
                    """
                    DELETE FROM hidden_listing h USING user_product up
                    WHERE h.user_product_id = up.id
                      AND up.user_id = :userId AND up.product_id = :productId
                      AND h.tracked_item_id = :itemId
                    """)
    int showListing(@Param("userId") long userId, @Param("productId") long productId, @Param("itemId") long itemId);
}
