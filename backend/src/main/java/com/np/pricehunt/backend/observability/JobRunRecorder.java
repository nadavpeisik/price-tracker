package com.np.pricehunt.backend.observability;

import com.np.pricehunt.backend.domain.JobStatus;
import com.np.pricehunt.backend.domain.ScheduledJobRun;
import com.np.pricehunt.backend.domain.ScheduledJobRunItem;
import com.np.pricehunt.backend.repository.ScheduledJobRunItemRepository;
import com.np.pricehunt.backend.repository.ScheduledJobRunRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// Each method uses REQUIRES_NEW so audit writes commit independently of the caller's
// transaction. The schedulers themselves are deliberately non-@Transactional (holding
// a DB connection across scraper network I/O would starve the pool); recorder calls
// are short-lived DB-only transactions that don't tangle with that constraint.
@Slf4j
@Component
@RequiredArgsConstructor
public class JobRunRecorder {

    private final ScheduledJobRunRepository runRepository;
    private final ScheduledJobRunItemRepository itemRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Long start(String jobName) {
        ScheduledJobRun run = ScheduledJobRun.builder()
                .jobName(jobName)
                .startedAt(Instant.now())
                .status(JobStatus.RUNNING)
                .correlationId(MDC.get("correlationId"))
                .build();
        return runRepository.save(run).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordItem(Long runId, String label, JobStatus status, long durationMs, String errorMessage) {
        ScheduledJobRun ref = runRepository.getReferenceById(runId);
        ScheduledJobRunItem item = ScheduledJobRunItem.builder()
                .run(ref)
                .label(label)
                .status(status)
                .durationMs(durationMs > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) durationMs)
                .errorMessage(errorMessage)
                .build();
        itemRepository.save(item);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void complete(Long runId, JobStatus status, int processed, int succeeded, int failed, String errorSummary) {
        ScheduledJobRun run = runRepository.findById(runId).orElseThrow();
        run.setFinishedAt(Instant.now());
        run.setStatus(status);
        run.setItemsProcessed(processed);
        run.setItemsSucceeded(succeeded);
        run.setItemsFailed(failed);
        run.setErrorSummary(errorSummary);
        runRepository.save(run);
    }
}
