package com.np.pricehunt.backend.scheduler;

import com.np.pricehunt.backend.domain.JobStatus;
import com.np.pricehunt.backend.observability.JobRunRecorder;
import com.np.pricehunt.backend.service.fx.ExchangeRateService;
import com.np.pricehunt.backend.service.fx.RateSnapshot;
import java.util.Optional;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RateRefreshScheduler {

    public static final String JOB_NAME = "FX_REFRESH";

    private final ExchangeRateService service;
    private final JobRunRecorder jobRunRecorder;

    public RateRefreshScheduler(ExchangeRateService service, JobRunRecorder jobRunRecorder) {
        this.service = service;
        this.jobRunRecorder = jobRunRecorder;
    }

    @Scheduled(cron = "${pricehunt.currency.fx.refresh-cron}", zone = "UTC")
    public void scheduledRefresh() {
        MDC.put("correlationId", "fx-" + UUID.randomUUID());
        Long runId = jobRunRecorder.start(JOB_NAME);
        try {
            log.info("Scheduled FX rate refresh starting");
            Optional<RateSnapshot> result = service.refresh();
            if (result.isPresent()) {
                int count = result.get().rates().size();
                jobRunRecorder.complete(runId, JobStatus.SUCCESS, count, count, 0, null);
            } else {
                jobRunRecorder.complete(
                        runId,
                        JobStatus.FAILED,
                        0,
                        0,
                        1,
                        "FX refresh failed (see logs for the correlationId stack trace)");
            }
        } catch (Exception e) {
            log.error("FX scheduled refresh aborted unexpectedly", e);
            jobRunRecorder.complete(
                    runId, JobStatus.FAILED, 0, 0, 1, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            MDC.clear();
        }
    }
}
