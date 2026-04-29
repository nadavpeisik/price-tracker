package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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
    // (Requires adding a 'lastChecked' LocalDateTime field to entity)
    List<TrackedItem> findByLastCheckedBefore(LocalDateTime threshold);
}
