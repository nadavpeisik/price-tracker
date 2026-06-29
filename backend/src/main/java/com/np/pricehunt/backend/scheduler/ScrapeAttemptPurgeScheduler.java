package com.np.pricehunt.backend.scheduler;

import com.np.pricehunt.backend.domain.JobStatus;
import com.np.pricehunt.backend.observability.JobRunRecorder;
import com.np.pricehunt.backend.observability.ScrapeAttemptRecorder;
import com.np.pricehunt.backend.repository.ScrapeAttemptRepository;
import com.np.pricehunt.backend.util.Throwables;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes expired {@code scrape_attempt} rows past their retention TTL (issue #131). Mirrors {@code
 * RateRefreshScheduler}: non-{@code @Transactional} (the per-batch delete carries its own tx via
 * {@code SimpleJpaRepository}), wrapped in {@code JobRunRecorder} for observability.
 *
 * <p>Deletes in <b>bounded chunks</b> — a page of ids, then {@code deleteAllByIdInBatch} — so one giant
 * delete can't lock the table or bloat WAL under a failure storm. A per-run cap ({@link
 * #MAX_BATCHES_PER_RUN}) bounds wall-clock so the single {@code @Scheduled} thread is never hogged away
 * from price refresh; any residual backlog drains over subsequent runs. A purge is one operation, so
 * it records {@code processed=1/succeeded=1} with the deleted row count in the log + summary.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScrapeAttemptPurgeScheduler {

    public static final String JOB_NAME = "SCRAPE_ATTEMPT_PURGE";

    // Package-private so the scheduler test can build a full page deterministically (early-break check).
    static final int BATCH_SIZE = 1000; // modest IN-list per delete; bounds lock + statement size
    private static final int MAX_BATCHES_PER_RUN = 100; // ~100k rows/run; backlog drains over days

    private final ScrapeAttemptRepository repository;
    private final JobRunRecorder jobRunRecorder;
    private final java.time.Clock clock;

    @Scheduled(cron = "${scrape.audit.purge-cron}", zone = "UTC")
    public void purgeExpired() {
        MDC.put(ScrapeAttemptRecorder.CORRELATION_ID_MDC_KEY, "purge-" + UUID.randomUUID());
        try {
            Long runId;
            try {
                runId = jobRunRecorder.start(JOB_NAME);
            } catch (Exception e) {
                log.error("Failed to start job run for {}", JOB_NAME, e);
                return;
            }

            int deleted = 0;
            Exception loopException = null;
            try {
                Instant cutoff = Instant.now(clock);
                Pageable page = PageRequest.of(0, BATCH_SIZE, Sort.by("retentionUntil", "id"));
                for (int batch = 0;
                        batch < MAX_BATCHES_PER_RUN && !Thread.currentThread().isInterrupted();
                        batch++) {
                    List<Long> ids = repository.findExpiredIds(cutoff, page);
                    if (!ids.isEmpty()) {
                        repository.deleteAllByIdInBatch(ids);
                        deleted += ids.size();
                    }
                    // A page smaller than BATCH_SIZE (incl. empty) means expired rows are exhausted — stop,
                    // skipping a wasted follow-up query. Single exit keeps the loop simple.
                    if (ids.size() < BATCH_SIZE) {
                        break;
                    }
                }
                log.info("Scrape-attempt purge deleted {} expired rows", deleted);
            } catch (Exception e) {
                log.error("Scrape-attempt purge aborted unexpectedly", e);
                loopException = e;
            }

            // A graceful shutdown may have interrupted the purge loop; clear the flag so the audit
            // completion's DB write isn't rejected by HikariCP/JDBC on an interrupted thread, then
            // restore it afterward so the runtime still sees the shutdown signal.
            boolean wasInterrupted = Thread.interrupted();
            try {
                JobStatus status = loopException != null ? JobStatus.FAILED : JobStatus.SUCCESS;
                String summary = loopException != null ? Throwables.summarize(loopException) : "deleted=" + deleted;
                jobRunRecorder.complete(
                        runId, status, 1, loopException != null ? 0 : 1, loopException != null ? 1 : 0, summary);
            } catch (Exception e) {
                log.error("Failed to record completion for runId {}", runId, e);
            } finally {
                if (wasInterrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            MDC.clear();
        }
    }
}
