package com.np.pricehunt.backend.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.domain.JobStatus;
import com.np.pricehunt.backend.domain.ScheduledJobRun;
import com.np.pricehunt.backend.domain.ScheduledJobRunItem;
import com.np.pricehunt.backend.repository.ScheduledJobRunItemRepository;
import com.np.pricehunt.backend.repository.ScheduledJobRunRepository;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class JobRunRecorderTest {

    @Mock
    private ScheduledJobRunRepository runRepository;

    @Mock
    private ScheduledJobRunItemRepository itemRepository;

    @InjectMocks
    private JobRunRecorder recorder;

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void start_persistsRunningRunWithCorrelationIdFromMdc() {
        MDC.put("correlationId", "sched-abc");
        when(runRepository.save(any(ScheduledJobRun.class))).thenAnswer(inv -> {
            ScheduledJobRun saved = inv.getArgument(0);
            saved.setId(42L);
            return saved;
        });

        Long runId = recorder.start("PRICE_REFRESH");

        assertThat(runId).isEqualTo(42L);
        ArgumentCaptor<ScheduledJobRun> captor = ArgumentCaptor.forClass(ScheduledJobRun.class);
        verify(runRepository).save(captor.capture());
        ScheduledJobRun persisted = captor.getValue();
        assertThat(persisted.getJobName()).isEqualTo("PRICE_REFRESH");
        assertThat(persisted.getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(persisted.getCorrelationId()).isEqualTo("sched-abc");
        assertThat(persisted.getStartedAt()).isNotNull();
    }

    @Test
    void start_acceptsAbsentCorrelationId() {
        when(runRepository.save(any(ScheduledJobRun.class))).thenAnswer(inv -> {
            ScheduledJobRun saved = inv.getArgument(0);
            saved.setId(7L);
            return saved;
        });

        Long runId = recorder.start("FX_REFRESH");

        assertThat(runId).isEqualTo(7L);
        ArgumentCaptor<ScheduledJobRun> captor = ArgumentCaptor.forClass(ScheduledJobRun.class);
        verify(runRepository).save(captor.capture());
        assertThat(captor.getValue().getCorrelationId()).isNull();
    }

    @Test
    void recordItem_persistsItemLinkedToRunReference() {
        ScheduledJobRun ref = ScheduledJobRun.builder().id(99L).build();
        when(runRepository.getReferenceById(99L)).thenReturn(ref);

        recorder.recordItem(99L, "https://shop.example/p/1", JobStatus.SUCCESS, 250L, null);

        ArgumentCaptor<ScheduledJobRunItem> captor = ArgumentCaptor.forClass(ScheduledJobRunItem.class);
        verify(itemRepository).save(captor.capture());
        ScheduledJobRunItem item = captor.getValue();
        assertThat(item.getRun()).isSameAs(ref);
        assertThat(item.getLabel()).isEqualTo("https://shop.example/p/1");
        assertThat(item.getStatus()).isEqualTo(JobStatus.SUCCESS);
        assertThat(item.getDurationMs()).isEqualTo(250);
        assertThat(item.getErrorMessage()).isNull();
    }

    @Test
    void recordItem_clampsDurationOverflowToIntegerMaxValue() {
        when(runRepository.getReferenceById(1L))
                .thenReturn(ScheduledJobRun.builder().id(1L).build());

        recorder.recordItem(1L, "label", JobStatus.FAILED, ((long) Integer.MAX_VALUE) + 1L, "boom");

        ArgumentCaptor<ScheduledJobRunItem> captor = ArgumentCaptor.forClass(ScheduledJobRunItem.class);
        verify(itemRepository).save(captor.capture());
        assertThat(captor.getValue().getDurationMs()).isEqualTo(Integer.MAX_VALUE);
        assertThat(captor.getValue().getErrorMessage()).isEqualTo("boom");
    }

    @Test
    void complete_mutatesManagedEntityWithoutExplicitSave() {
        ScheduledJobRun existing = ScheduledJobRun.builder()
                .id(5L)
                .jobName("PRICE_REFRESH")
                .startedAt(Instant.parse("2026-06-04T10:00:00Z"))
                .status(JobStatus.RUNNING)
                .build();
        when(runRepository.findById(5L)).thenReturn(Optional.of(existing));

        recorder.complete(5L, JobStatus.PARTIAL, 10, 7, 3, "RuntimeException: scraper down");

        verify(runRepository, never()).save(any(ScheduledJobRun.class));
        assertThat(existing.getStatus()).isEqualTo(JobStatus.PARTIAL);
        assertThat(existing.getItemsProcessed()).isEqualTo(10);
        assertThat(existing.getItemsSucceeded()).isEqualTo(7);
        assertThat(existing.getItemsFailed()).isEqualTo(3);
        assertThat(existing.getErrorSummary()).isEqualTo("RuntimeException: scraper down");
        assertThat(existing.getFinishedAt()).isNotNull();
    }

    @Test
    void complete_throwsWhenRunMissing() {
        when(runRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> recorder.complete(404L, JobStatus.SUCCESS, 0, 0, 0, null))
                .isInstanceOf(NoSuchElementException.class);
        verifyNoInteractions(itemRepository);
    }
}
