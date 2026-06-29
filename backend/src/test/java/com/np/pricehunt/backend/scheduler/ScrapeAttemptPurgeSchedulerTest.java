package com.np.pricehunt.backend.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.domain.JobStatus;
import com.np.pricehunt.backend.observability.JobRunRecorder;
import com.np.pricehunt.backend.repository.ScrapeAttemptRepository;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ScrapeAttemptPurgeSchedulerTest {

    private static final long RUN_ID = 99L;

    @Mock
    private ScrapeAttemptRepository repository;

    @Mock
    private JobRunRecorder jobRunRecorder;

    private ScrapeAttemptPurgeScheduler scheduler;

    @BeforeEach
    void setUp() {
        // Construct here, not as a field initializer: @Mock fields are injected after field init.
        scheduler = new ScrapeAttemptPurgeScheduler(
                repository,
                jobRunRecorder,
                Clock.fixed(java.time.Instant.parse("2026-06-29T03:15:00Z"), ZoneOffset.UTC));
    }

    @Test
    void purge_partialPage_stopsAfterOneQuery_andReportsCount() {
        when(jobRunRecorder.start(ScrapeAttemptPurgeScheduler.JOB_NAME)).thenReturn(RUN_ID);
        // A page smaller than BATCH_SIZE means expired rows are exhausted — the loop must stop WITHOUT a
        // follow-up (empty) query.
        when(repository.findExpiredIds(any(), any())).thenReturn(List.of(1L, 2L, 3L));

        scheduler.purgeExpired();

        verify(repository).deleteAllByIdInBatch(List.of(1L, 2L, 3L));
        verify(repository).findExpiredIds(any(), any()); // exactly once — no empty follow-up query
        verify(jobRunRecorder).complete(RUN_ID, JobStatus.SUCCESS, 1, 1, 0, "deleted=3");
    }

    @Test
    void purge_fullPageThenPartial_loopsThenStops() {
        when(jobRunRecorder.start(ScrapeAttemptPurgeScheduler.JOB_NAME)).thenReturn(RUN_ID);
        // A full page (== BATCH_SIZE) does NOT short-circuit, so the loop queries again; the next (partial)
        // page then stops it. Proves the loop iterates past one batch and sums the count.
        List<Long> fullPage = java.util.stream.LongStream.rangeClosed(1, ScrapeAttemptPurgeScheduler.BATCH_SIZE)
                .boxed()
                .toList();
        when(repository.findExpiredIds(any(), any())).thenReturn(fullPage).thenReturn(List.of(9999L));

        scheduler.purgeExpired();

        verify(repository).deleteAllByIdInBatch(fullPage);
        verify(repository).deleteAllByIdInBatch(List.of(9999L));
        verify(repository, times(2)).findExpiredIds(any(), any());
        verify(jobRunRecorder)
                .complete(
                        RUN_ID, JobStatus.SUCCESS, 1, 1, 0, "deleted=" + (ScrapeAttemptPurgeScheduler.BATCH_SIZE + 1));
    }

    @Test
    void purge_repoFailure_recordsFailedCompletion() {
        when(jobRunRecorder.start(ScrapeAttemptPurgeScheduler.JOB_NAME)).thenReturn(RUN_ID);
        when(repository.findExpiredIds(any(), any())).thenThrow(new RuntimeException("db down"));

        scheduler.purgeExpired();

        verify(jobRunRecorder).complete(eq(RUN_ID), eq(JobStatus.FAILED), eq(1), eq(0), eq(1), anyString());
    }

    @Test
    void purge_nothingExpired_completesSuccessWithZero() {
        when(jobRunRecorder.start(ScrapeAttemptPurgeScheduler.JOB_NAME)).thenReturn(RUN_ID);
        when(repository.findExpiredIds(any(), any())).thenReturn(List.of());

        scheduler.purgeExpired();

        verify(repository, org.mockito.Mockito.never()).deleteAllByIdInBatch(any());
        verify(jobRunRecorder).complete(RUN_ID, JobStatus.SUCCESS, 1, 1, 0, "deleted=0");
    }
}
