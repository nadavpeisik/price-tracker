package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.TrackedItemRefreshView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

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

    // Narrow projection for the scheduler — avoids hydrating the LAZY priceHistory collection
    // that @Data's generated toString/equals/hashCode would touch.
    @Query("""
           SELECT new com.np.pricehunt.backend.dto.TrackedItemRefreshView(t.id, t.url, t.lastChecked)
           FROM TrackedItem t
           """)
    List<TrackedItemRefreshView> findAllForRefresh();
}
