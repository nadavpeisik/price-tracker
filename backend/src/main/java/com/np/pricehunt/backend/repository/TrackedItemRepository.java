package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.ShopNameSource;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.TrackedItemRefreshView;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface TrackedItemRepository extends JpaRepository<TrackedItem, Long> {

    // 1. Find all shop links for a specific product
    // Use this to show a "Price Comparison" table for one item.
    List<TrackedItem> findByProduct(Product product);

    // 2. Prevent Duplicate URLs
    // Before adding a new URL, check if we are already tracking it.
    Optional<TrackedItem> findByUrl(String url);

    // 3. Get items that haven't been checked in a while
    // Essential for "Daily Scraper" logic later.
    List<TrackedItem> findByLastCheckedBefore(Instant threshold);

    // Narrow projection + DB-side stale filter for the scheduler. Avoids hydrating the LAZY
    // priceHistory collection (the @Data toString/equals/hashCode foot-gun) and keeps freshly-
    // refreshed rows from ever reaching the JVM.
    @Query(
            """
           SELECT new com.np.pricehunt.backend.dto.TrackedItemRefreshView(t.id, t.url, t.lastChecked)
           FROM TrackedItem t
           WHERE t.lastChecked IS NULL OR t.lastChecked < :cutoff
           """)
    List<TrackedItemRefreshView> findStaleItems(@Param("cutoff") Instant cutoff);

    /**
     * Atomic compare-and-set of the shop name: overwrites only when {@code newSource} ranks at or
     * above the current source (so a concurrent lower-precedence write can't clobber a higher one),
     * and a {@code HOST_FALLBACK} never replaces an existing non-blank name (but still fills a blank
     * one). The precedence lives only in {@link ShopNameSource} — passed in as the allowed-prior
     * set — never in SQL.
     *
     * <p>This is a direct by-id bulk update that bypasses the persistence context, so it must be
     * called in a transaction that does not hold a managed {@link TrackedItem} (the lifecycle writes
     * only by id and never loads the entity). That prevents a stale managed copy from flushing over
     * this write — without resorting to a context-wide {@code clearAutomatically}.
     *
     * @return true if the row was updated, false if the guard rejected it
     */
    default boolean applyShopName(Long itemId, String name, ShopNameSource newSource) {
        boolean allowOverwriteNonBlank = newSource != ShopNameSource.HOST_FALLBACK;
        return applyShopName(itemId, name, newSource, newSource.atOrBelow(), allowOverwriteNonBlank) > 0;
    }

    @Modifying
    @Query(
            """
           UPDATE TrackedItem t SET t.shopName = :name, t.shopNameSource = :newSource
           WHERE t.id = :id
             AND (t.shopNameSource IS NULL OR t.shopNameSource IN :allowedSources)
             AND (:allowOverwriteNonBlank = true OR t.shopName IS NULL OR TRIM(t.shopName) = '')
           """)
    int applyShopName(
            @Param("id") Long itemId,
            @Param("name") String name,
            @Param("newSource") ShopNameSource newSource,
            @Param("allowedSources") Collection<ShopNameSource> allowedSources,
            @Param("allowOverwriteNonBlank") boolean allowOverwriteNonBlank);
}
