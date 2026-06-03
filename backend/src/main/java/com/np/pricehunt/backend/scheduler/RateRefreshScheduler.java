package com.np.pricehunt.backend.scheduler;

import com.np.pricehunt.backend.service.fx.ExchangeRateService;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RateRefreshScheduler {

    private final ExchangeRateService service;

    public RateRefreshScheduler(ExchangeRateService service) {
        this.service = service;
    }

    @Scheduled(cron = "${pricehunt.currency.fx.refresh-cron}", zone = "UTC")
    public void scheduledRefresh() {
        MDC.put("correlationId", "fx-" + UUID.randomUUID());
        try {
            log.info("Scheduled FX rate refresh starting");
            service.refresh();
        } finally {
            MDC.clear();
        }
    }
}
