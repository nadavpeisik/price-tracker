package com.np.pricehunt.backend.scheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.domain.JobStatus;
import com.np.pricehunt.backend.observability.JobRunRecorder;
import com.np.pricehunt.backend.service.fx.ExchangeRateService;
import com.np.pricehunt.backend.service.fx.RateSnapshot;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

@ExtendWith(MockitoExtension.class)
class RateRefreshSchedulerTest {

    @Mock
    private ExchangeRateService service;

    @Mock
    private JobRunRecorder jobRunRecorder;

    @InjectMocks
    private RateRefreshScheduler scheduler;

    @BeforeEach
    void stubStart() {
        when(jobRunRecorder.start(RateRefreshScheduler.JOB_NAME)).thenReturn(101L);
    }

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void scheduledRefresh_success_recordsSuccess() {
        RateSnapshot snapshot = new RateSnapshot(
                LocalDate.parse("2026-06-04"),
                Map.of(
                        "USD", new BigDecimal("1.07"),
                        "ILS", new BigDecimal("3.95"),
                        "GBP", new BigDecimal("0.85")));
        when(service.refresh()).thenReturn(Optional.of(snapshot));

        scheduler.scheduledRefresh();

        verify(jobRunRecorder).complete(101L, JobStatus.SUCCESS, 1, 1, 0, null);
    }

    @Test
    void scheduledRefresh_emptyResult_recordsFailedWithSummary() {
        when(service.refresh()).thenReturn(Optional.empty());

        scheduler.scheduledRefresh();

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(jobRunRecorder).complete(eq(101L), eq(JobStatus.FAILED), eq(1), eq(0), eq(1), summary.capture());
        assertThat(summary.getValue()).contains("FX refresh failed");
    }

    @Test
    void scheduledRefresh_serviceThrows_recordsFailedWithExceptionSummary() {
        when(service.refresh()).thenThrow(new RuntimeException("ECB unreachable"));

        scheduler.scheduledRefresh();

        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(jobRunRecorder).complete(eq(101L), eq(JobStatus.FAILED), eq(1), eq(0), eq(1), summary.capture());
        assertThat(summary.getValue()).isEqualTo("RuntimeException: ECB unreachable");
    }

    @Test
    void scheduledRefresh_clearsMdcAfterRun() {
        when(service.refresh()).thenReturn(Optional.of(new RateSnapshot(LocalDate.parse("2026-06-04"), Map.of())));

        scheduler.scheduledRefresh();

        assertThat(MDC.get("correlationId")).isNull();
        verify(jobRunRecorder).complete(any(), any(), anyInt(), anyInt(), anyInt(), any());
    }
}
