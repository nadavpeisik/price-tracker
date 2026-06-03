package com.np.pricehunt.backend.scheduler;

import com.np.pricehunt.backend.dto.TrackedItemRefreshView;
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

    private static final String DEFAULT_FIXED_DELAY_MS = "21600000";

    private final ProductTrackingService trackingService;
    private final TrackedItemRepository trackedItemRepository;
    private final long fixedDelayMs;

    public PriceCheckScheduler(
            ProductTrackingService trackingService,
            TrackedItemRepository trackedItemRepository,
            @Value("${price.scheduler.fixed-delay-ms:" + DEFAULT_FIXED_DELAY_MS + "}") long fixedDelayMs) {
        this.trackingService = trackingService;
        this.trackedItemRepository = trackedItemRepository;
        this.fixedDelayMs = fixedDelayMs;
    }

    @Scheduled(
            fixedDelayString = "${price.scheduler.fixed-delay-ms:" + DEFAULT_FIXED_DELAY_MS + "}",
            initialDelayString = "${price.scheduler.initial-delay-ms:60000}")
    public void refreshAll() {
        MDC.put("correlationId", "sched-" + UUID.randomUUID());
        try {
            Instant cutoff = Instant.now().minusMillis(fixedDelayMs);
            List<TrackedItemRefreshView> items = trackedItemRepository.findStaleItems(cutoff);
            log.info("Scheduled refresh starting for {} stale items", items.size());
            int success = 0, failed = 0;
            for (TrackedItemRefreshView item : items) {
                try {
                    trackingService.scheduledRefresh(item.id());
                    success++;
                } catch (Exception e) {
                    failed++;
                    log.warn(
                            "Scheduled refresh failed for itemId={} url={} type={}: {}",
                            item.id(),
                            item.url(),
                            e.getClass().getSimpleName(),
                            e.getMessage());
                }
            }
            log.info("Scheduled refresh done: {} success, {} failed", success, failed);
        } finally {
            MDC.clear();
        }
    }
}
