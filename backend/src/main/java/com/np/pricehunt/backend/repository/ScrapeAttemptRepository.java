package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.ScrapeAttempt;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ScrapeAttemptRepository extends JpaRepository<ScrapeAttempt, Long> {

    /**
     * One bounded page of expired-row ids, oldest first, for the chunked purge (issue #131). Selecting
     * ids then deleting via the built-in {@code deleteAllByIdInBatch} (itself {@code @Transactional} in
     * {@code SimpleJpaRepository}) keeps the purge db-agnostic (H2 + Postgres) — no native
     * {@code DELETE … LIMIT} — and bounds each delete's lock footprint. The {@code Pageable} must carry
     * a deterministic sort (e.g. {@code retentionUntil, id}) so successive pages don't overlap.
     */
    @Query("SELECT a.id FROM ScrapeAttempt a WHERE a.retentionUntil < :cutoff")
    List<Long> findExpiredIds(@Param("cutoff") Instant cutoff, Pageable page);
}
