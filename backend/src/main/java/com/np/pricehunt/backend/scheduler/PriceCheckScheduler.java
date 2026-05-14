package com.np.pricehunt.backend.scheduler;

import com.np.pricehunt.backend.dto.TrackedItemRefreshView;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.service.ProductTrackingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "price.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class PriceCheckScheduler {

    private final ProductTrackingService trackingService;
    private final TrackedItemRepository trackedItemRepository;

    @Value("${price.scheduler.fixed-delay-ms:21600000}")
    private long fixedDelayMs;

    @Scheduled(
            fixedDelayString = "${price.scheduler.fixed-delay-ms:21600000}",
            initialDelayString = "${price.scheduler.initial-delay-ms:60000}"
    )
    public void refreshAll() {
        MDC.put("correlationId", "sched-" + UUID.randomUUID());
        try {
            List<TrackedItemRefreshView> items = trackedItemRepository.findAllForRefresh();
            Instant staleCutoff = Instant.now().minusMillis(fixedDelayMs);
            log.info("Scheduled refresh starting for {} items", items.size());
            int success = 0, failed = 0, skipped = 0;
            for (TrackedItemRefreshView item : items) {
                if (item.lastChecked() != null && item.lastChecked().isAfter(staleCutoff)) {
                    skipped++;
                    continue;
                }
                try {
                    trackingService.scheduledRefresh(item.id());
                    success++;
                } catch (Exception e) {
                    failed++;
                    log.warn("Scheduled refresh failed for itemId={} url={} type={}: {}",
                            item.id(), item.url(), e.getClass().getSimpleName(), e.getMessage());
                }
            }
            log.info("Scheduled refresh done: {} success, {} failed, {} skipped (already fresh)", success, failed, skipped);
        } finally {
            MDC.clear();
        }
    }
}
