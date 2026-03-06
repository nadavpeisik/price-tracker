package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.TrackedItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PriceRecordRepository extends JpaRepository<PriceRecord, Long> {

    // 1. Get the full history for a specific store link, newest first
    List<PriceRecord> findByTrackedItemOrderByTimestampDesc(TrackedItem trackedItem);

    // 2. Get ONLY the very latest price for a store link
    Optional<PriceRecord> findFirstByTrackedItemOrderByTimestampDesc(TrackedItem trackedItem);

    // 3. Find prices within a specific date range
    List<PriceRecord> findByTrackedItemAndTimestampBetween(
            TrackedItem trackedItem,
            LocalDateTime start,
            LocalDateTime end
    );
}
