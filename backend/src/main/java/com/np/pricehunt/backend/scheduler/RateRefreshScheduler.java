package com.np.pricehunt.backend.scheduler;

import com.np.pricehunt.backend.domain.JobStatus;
import com.np.pricehunt.backend.observability.JobRunRecorder;
import com.np.pricehunt.backend.service.fx.ExchangeRateService;
import com.np.pricehunt.backend.service.fx.RateSnapshot;
import com.np.pricehunt.backend.util.Throwables;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RateRefreshScheduler {

    public static final String JOB_NAME = "FX_REFRESH";

    private final ExchangeRateService service;
    private final JobRunRecorder jobRunRecorder;

    @Scheduled(cron = "${pricehunt.currency.fx.refresh-cron}", zone = "UTC")
    public void scheduledRefresh() {
        MDC.put("correlationId", "fx-" + UUID.randomUUID());
        try {
            Long runId;
            try {
                runId = jobRunRecorder.start(JOB_NAME);
            } catch (Exception e) {
                log.error("Failed to start job run for {}", JOB_NAME, e);
                return;
            }

            int succeeded = 0;
            int failed = 0;
            String errorSummary = null;
            Exception loopException = null;
            try {
                log.info("Scheduled FX rate refresh starting");
                Optional<RateSnapshot> result = service.refresh();
                if (result.isPresent()) {
                    succeeded = 1;
                    log.info(
                            "FX refresh persisted {} rates",
                            result.get().rates().size());
                } else {
                    failed = 1;
                    errorSummary = "FX refresh failed (see logs for the correlationId stack trace)";
                }
            } catch (Exception e) {
                log.error("FX scheduled refresh loop aborted unexpectedly", e);
                loopException = e;
                failed = 1;
            }

            try {
                JobStatus status = (loopException != null || failed > 0) ? JobStatus.FAILED : JobStatus.SUCCESS;
                String summary = loopException != null ? Throwables.summarize(loopException) : errorSummary;
                jobRunRecorder.complete(runId, status, 1, succeeded, failed, summary);
            } catch (Exception e) {
                log.error("Failed to record completion for runId {}", runId, e);
            }
        } finally {
            MDC.clear();
        }
    }
}
