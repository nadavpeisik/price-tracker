package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.TrendRecordView;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PriceRecordRepository extends JpaRepository<PriceRecord, Long> {

    // 1. Get the full history for a specific store link, newest first
    List<PriceRecord> findByTrackedItemOrderByTimestampDesc(TrackedItem trackedItem);

    // 2. Get ONLY the very latest price for a store link
    Optional<PriceRecord> findFirstByTrackedItemOrderByTimestampDesc(TrackedItem trackedItem);

    // 3. Find prices within a specific date range, newest first
    List<PriceRecord> findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
            TrackedItem trackedItem, Instant start, Instant end);

    /**
     * Latest record for a listing at or before {@code cutoff}, ties broken by id.
     *
     * <p>The cutoff belongs in the query rather than a post-filter: a future-dated record (clock
     * skew, a manual insert) must not shadow the valid earlier record that the trend calculator
     * would select, or the dashboard row and the series would disagree. The id tiebreak makes
     * "latest" deterministic when two records share a timestamp.
     */
    Optional<PriceRecord> findFirstByTrackedItemAndTimestampLessThanEqualOrderByTimestampDescIdDesc(
            TrackedItem trackedItem, Instant cutoff);

    /**
     * One batched window fetch across many listings for the price-trend engine (issue #145).
     *
     * <p>Constructor projection (not entities) keeps the lazy {@code trackedItem} association out of
     * the result set; the {@code (trackedItem, timestamp, id)} ordering lets the calculator walk each
     * listing with a single forward pointer and makes latest-record selection deterministic when
     * timestamps collide. Covered by {@code idx_price_record_item_timestamp}.
     *
     * <p>Callers must skip this query when {@code itemIds} is empty rather than binding an empty
     * {@code IN} list.
     */
    @Query(
            """
            SELECT new com.np.pricehunt.backend.dto.TrendRecordView(
                r.trackedItem.id, r.price, r.currency, r.availability, r.timestamp)
            FROM PriceRecord r
            WHERE r.trackedItem.id IN :itemIds AND r.timestamp >= :from AND r.timestamp <= :to
            ORDER BY r.trackedItem.id ASC, r.timestamp ASC, r.id ASC
            """)
    List<TrendRecordView> findTrendRecords(
            @Param("itemIds") Collection<Long> itemIds, @Param("from") Instant from, @Param("to") Instant to);
}
