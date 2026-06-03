package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.TrackedItemRefreshView;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
