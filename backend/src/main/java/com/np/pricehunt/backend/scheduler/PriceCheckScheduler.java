package com.np.pricehunt.backend.scheduler;

import com.np.pricehunt.backend.domain.JobStatus;
import com.np.pricehunt.backend.dto.TrackedItemRefreshView;
import com.np.pricehunt.backend.observability.JobRunRecorder;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.service.ProductTrackingService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ConditionalOnProperty(name = "price.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class PriceCheckScheduler {

    public static final String JOB_NAME = "PRICE_REFRESH";
    private static final String DEFAULT_FIXED_DELAY_MS = "21600000";

    private final ProductTrackingService trackingService;
    private final TrackedItemRepository trackedItemRepository;
    private final JobRunRecorder jobRunRecorder;
    private final long fixedDelayMs;

    public PriceCheckScheduler(
            ProductTrackingService trackingService,
            TrackedItemRepository trackedItemRepository,
            JobRunRecorder jobRunRecorder,
            @Value("${price.scheduler.fixed-delay-ms:" + DEFAULT_FIXED_DELAY_MS + "}") long fixedDelayMs) {
        this.trackingService = trackingService;
        this.trackedItemRepository = trackedItemRepository;
        this.jobRunRecorder = jobRunRecorder;
        this.fixedDelayMs = fixedDelayMs;
    }

    @Scheduled(
            fixedDelayString = "${price.scheduler.fixed-delay-ms:" + DEFAULT_FIXED_DELAY_MS + "}",
            initialDelayString = "${price.scheduler.initial-delay-ms:60000}")
    public void refreshAll() {
        MDC.put("correlationId", "sched-" + UUID.randomUUID());
        Long runId = jobRunRecorder.start(JOB_NAME);
        int success = 0;
        int failed = 0;
        Exception lastException = null;
        try {
            Instant cutoff = Instant.now().minusMillis(fixedDelayMs);
            List<TrackedItemRefreshView> items = trackedItemRepository.findStaleItems(cutoff);
            log.info("Scheduled refresh starting for {} stale items", items.size());
            for (TrackedItemRefreshView item : items) {
                long startMs = System.currentTimeMillis();
                try {
                    trackingService.scheduledRefresh(item.id());
                    success++;
                    jobRunRecorder.recordItem(
                            runId, item.url(), JobStatus.SUCCESS, System.currentTimeMillis() - startMs, null);
                } catch (Exception e) {
                    failed++;
                    lastException = e;
                    log.warn(
                            "Scheduled refresh failed for itemId={} url={} type={}: {}",
                            item.id(),
                            item.url(),
                            e.getClass().getSimpleName(),
                            e.getMessage());
                    jobRunRecorder.recordItem(
                            runId,
                            item.url(),
                            JobStatus.FAILED,
                            System.currentTimeMillis() - startMs,
                            e.getClass().getSimpleName() + ": " + e.getMessage());
                }
            }
            log.info("Scheduled refresh done: {} success, {} failed", success, failed);
            jobRunRecorder.complete(
                    runId,
                    computeFinalStatus(success, failed),
                    success + failed,
                    success,
                    failed,
                    lastException == null ? null : summarize(lastException));
        } catch (Exception e) {
            log.error("Scheduled refresh aborted unexpectedly", e);
            jobRunRecorder.complete(runId, JobStatus.FAILED, success + failed, success, failed, summarize(e));
        } finally {
            MDC.clear();
        }
    }

    private static JobStatus computeFinalStatus(int success, int failed) {
        if (failed == 0) return JobStatus.SUCCESS;
        if (success == 0) return JobStatus.FAILED;
        return JobStatus.PARTIAL;
    }

    private static String summarize(Exception e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }
}
