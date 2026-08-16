package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.CutoffObservationRow;
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

    /**
     * Every listing's latest observation at each of the dashboard's two evaluation instants — at most
     * two rows per listing, for the whole tracked set, in one query (issue #146).
     *
     * <p><b>Why this exists.</b> The dashboard needs {@code delta7d} for every product on every
     * request (both summary tiles aggregate it), but it does not need the full history the trend
     * engine fetches for sparklines. Selecting only the two records the delta actually reads turns an
     * unbounded per-listing history fetch into a bounded ≤2-rows-per-listing pass.
     *
     * <p><b>Why latest-1 per cutoff is the exact engine semantics.</b> {@code
     * PriceTrendCalculator.bestOfferAsOf} takes each listing's single latest record at or before the
     * cutoff and then applies {@link com.np.pricehunt.backend.service.trend.TrendEligibility} to it —
     * it never falls back to an older record when that one is ineligible, because an UNAVAILABLE
     * latest observation is meant to cancel carry-forward rather than resurrect a stale in-stock
     * price. So fetching one record per cutoff loses nothing, and the TTL floors below are safe: a
     * record older than the floor would be fetched by the engine and then rejected on the TTL rule.
     * <b>If that fallback rule ever changes, this query silently under-fetches</b> — the equivalence
     * fixtures in {@code DashboardQueryIntegrationTest} are what catch it.
     *
     * <p><b>Why the ranking is per-branch.</b> Each UNION ALL branch computes its own {@code
     * ROW_NUMBER}, so a listing is ranked once within the CURRENT window and once within the BASELINE
     * window. Ranking after the union instead would need {@code PARTITION BY tracked_item_id, side};
     * forgetting {@code side} there ranks the baseline row second behind the current row and nulls
     * every delta. Doing it per branch makes that mistake unrepresentable.
     *
     * <p>The two windows overlap when {@code carryForwardDays >= 7}, so a single record can be
     * selected by both branches and returned twice with different {@code side} values. That is
     * correct: the two sides are independent evaluations that happen to land on the same observation.
     *
     * <p><b>Aliases are quoted deliberately</b> — Postgres folds unquoted identifiers to lowercase,
     * and the interface projection binds by exact column label. {@code "timestamp"} is quoted for the
     * other reason: it is the column's real (reserved-word) name from V1.
     *
     * <p>Whole-set by design: single-tenant, so there is no item-id IN list to bind. Served by {@code
     * idx_price_record_timestamp} (V10); the composite {@code (tracked_item_id, "timestamp")} cannot
     * help because its leading column does not appear in the predicate.
     *
     * @param currentFloor oldest observation the CURRENT side may carry forward from (inclusive)
     * @param asOf the request instant; nothing after it may be selected (inclusive)
     * @param baselineFloor oldest observation the BASELINE side may carry forward from (inclusive)
     * @param baselineCutoff {@code asOf − 7d}, the BASELINE side's evaluation instant (inclusive)
     */
    @Query(
            nativeQuery = true,
            value =
                    """
                    SELECT r.tracked_item_id     AS "trackedItemId",
                           r.id                  AS "recordId",
                           r.price               AS "price",
                           r.currency            AS "currency",
                           r.availability_status AS "availability",
                           r.observed_at         AS "observedAt",
                           r.side                AS "side"
                    FROM (
                        SELECT c.tracked_item_id, c.id, c.price, c.currency, c.availability_status,
                               c."timestamp" AS observed_at,
                               'CURRENT' AS side,
                               ROW_NUMBER() OVER (
                                   PARTITION BY c.tracked_item_id
                                   ORDER BY c."timestamp" DESC, c.id DESC) AS rn
                        FROM price_record c
                        WHERE c."timestamp" >= :currentFloor AND c."timestamp" <= :asOf
                        UNION ALL
                        SELECT b.tracked_item_id, b.id, b.price, b.currency, b.availability_status,
                               b."timestamp" AS observed_at,
                               'BASELINE' AS side,
                               ROW_NUMBER() OVER (
                                   PARTITION BY b.tracked_item_id
                                   ORDER BY b."timestamp" DESC, b.id DESC) AS rn
                        FROM price_record b
                        WHERE b."timestamp" >= :baselineFloor AND b."timestamp" <= :baselineCutoff
                    ) r
                    WHERE r.rn = 1
                    """)
    List<CutoffObservationRow> findCutoffObservations(
            @Param("currentFloor") Instant currentFloor,
            @Param("asOf") Instant asOf,
            @Param("baselineFloor") Instant baselineFloor,
            @Param("baselineCutoff") Instant baselineCutoff);
}
