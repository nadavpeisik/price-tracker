package com.np.pricehunt.backend.scheduler;

import com.np.pricehunt.backend.config.PriceSchedulerProperties;
import com.np.pricehunt.backend.domain.JobStatus;
import com.np.pricehunt.backend.dto.TrackedItemRefreshView;
import com.np.pricehunt.backend.observability.JobRunRecorder;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.service.ProductTrackingService;
import com.np.pricehunt.backend.util.Throwables;
import com.np.pricehunt.backend.util.Timing;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "price.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class PriceCheckScheduler {

    public static final String JOB_NAME = "PRICE_REFRESH";

    private final ProductTrackingService trackingService;
    private final TrackedItemRepository trackedItemRepository;
    private final JobRunRecorder jobRunRecorder;
    private final PriceSchedulerProperties schedulerProperties;

    // Placeholders share PriceSchedulerProperties' default constants so the @Scheduled cadence and
    // the bound fixedDelay (reused as the stale cutoff below) can never resolve to different defaults.
    @Scheduled(
            fixedDelayString = "${price.scheduler.fixed-delay:" + PriceSchedulerProperties.DEFAULT_FIXED_DELAY + "}",
            initialDelayString =
                    "${price.scheduler.initial-delay:" + PriceSchedulerProperties.DEFAULT_INITIAL_DELAY + "}")
    public void refreshAll() {
        MDC.put("correlationId", "sched-" + UUID.randomUUID());
        try {
            Long runId;
            try {
                runId = jobRunRecorder.start(JOB_NAME);
            } catch (Exception e) {
                log.error("Failed to start job run for {}", JOB_NAME, e);
                return;
            }

            int success = 0;
            int failed = 0;
            Exception loopException = null;
            try {
                Instant cutoff = Instant.now().minus(schedulerProperties.fixedDelay());
                List<TrackedItemRefreshView> items = trackedItemRepository.findStaleItems(cutoff);
                log.info("Scheduled refresh starting for {} stale items", items.size());
                for (TrackedItemRefreshView item : items) {
                    long startNanos = System.nanoTime();
                    JobStatus itemStatus;
                    String itemError = null;
                    try {
                        trackingService.scheduledRefresh(item.id());
                        itemStatus = JobStatus.SUCCESS;
                        success++;
                    } catch (Exception e) {
                        itemStatus = JobStatus.FAILED;
                        itemError = Throwables.summarize(e);
                        failed++;
                        log.warn(
                                "Scheduled refresh failed for itemId={} url={} type={}: {}",
                                item.id(),
                                item.url(),
                                e.getClass().getSimpleName(),
                                e.getMessage());
                    }
                    try {
                        jobRunRecorder.recordItem(
                                runId, item.url(), itemStatus, Timing.elapsedMs(startNanos), itemError);
                    } catch (Exception e) {
                        log.warn("Failed to record item for url={}: {}", item.url(), e.getMessage());
                    }
                }
                log.info("Scheduled refresh done: {} success, {} failed", success, failed);
            } catch (Exception e) {
                log.error("Scheduled refresh loop aborted unexpectedly", e);
                loopException = e;
            }

            try {
                JobStatus status = loopException != null ? JobStatus.FAILED : computeFinalStatus(success, failed);
                jobRunRecorder.complete(
                        runId, status, success + failed, success, failed, Throwables.summarize(loopException));
            } catch (Exception e) {
                log.error("Failed to record completion for runId {}", runId, e);
            }
        } finally {
            MDC.clear();
        }
    }

    private static JobStatus computeFinalStatus(int success, int failed) {
        if (failed == 0) return JobStatus.SUCCESS;
        if (success == 0) return JobStatus.FAILED;
        return JobStatus.PARTIAL;
    }
}
