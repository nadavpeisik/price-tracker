package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.TrackedItem;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
