package com.np.pricehunt.backend.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

import com.np.pricehunt.backend.config.PriceSchedulerProperties;
import com.np.pricehunt.backend.domain.JobStatus;
import com.np.pricehunt.backend.observability.JobRunRecorder;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.repository.projection.TrackedItemRefreshView;
import com.np.pricehunt.backend.service.ProductTrackingService;
import com.np.pricehunt.backend.validator.UrlValidator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PriceCheckSchedulerTest {

    private static final Duration FIXED_DELAY = Duration.ofHours(6);
    private static final Long RUN_ID = 99L;

    @Mock
    private ProductTrackingService trackingService;

    @Mock
    private TrackedItemRepository trackedItemRepository;

    @Mock
    private JobRunRecorder jobRunRecorder;

    @Mock
    private UrlValidator urlValidator;

    private PriceCheckScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PriceCheckScheduler(
                trackingService,
                trackedItemRepository,
                jobRunRecorder,
                new PriceSchedulerProperties(FIXED_DELAY, Duration.ofMinutes(1)),
                urlValidator);
        when(jobRunRecorder.start(PriceCheckScheduler.JOB_NAME)).thenReturn(RUN_ID);
    }

    @Test
    void refreshAll_callsScheduledRefreshForEachItem() {
        Instant old = Instant.now().minusSeconds(60 * 60 * 24);
        List<TrackedItemRefreshView> items = List.of(
                new TrackedItemRefreshView(1L, "https://a.com/1", old),
                new TrackedItemRefreshView(2L, "https://b.com/2", old),
                new TrackedItemRefreshView(3L, "https://c.com/3", null));
        when(trackedItemRepository.findStaleItems(any(Instant.class))).thenReturn(items);

        scheduler.refreshAll();

        verify(trackingService).scheduledRefresh(1L);
        verify(trackingService).scheduledRefresh(2L);
        verify(trackingService).scheduledRefresh(3L);
        verifyNoMoreInteractions(trackingService);

        verify(jobRunRecorder).start(PriceCheckScheduler.JOB_NAME);
        verify(jobRunRecorder, times(3))
                .recordItem(eq(RUN_ID), any(String.class), eq(JobStatus.SUCCESS), anyLong(), isNull());
        verify(jobRunRecorder).complete(eq(RUN_ID), eq(JobStatus.SUCCESS), eq(3), eq(3), eq(0), isNull());
    }

    @Test
    void refreshAll_singleItemFailureDoesNotStopOthers() {
        Instant old = Instant.now().minusSeconds(60 * 60 * 24);
        List<TrackedItemRefreshView> items = List.of(
                new TrackedItemRefreshView(1L, "https://a.com/1", old),
                new TrackedItemRefreshView(2L, "https://b.com/2", old),
                new TrackedItemRefreshView(3L, "https://c.com/3", old));
        when(trackedItemRepository.findStaleItems(any(Instant.class))).thenReturn(items);
        when(trackingService.scheduledRefresh(1L)).thenReturn(null);
        when(trackingService.scheduledRefresh(3L)).thenReturn(null);
        when(trackingService.scheduledRefresh(2L)).thenThrow(new RuntimeException("scraper down"));

        scheduler.refreshAll();

        verify(trackingService).scheduledRefresh(1L);
        verify(trackingService).scheduledRefresh(2L);
        verify(trackingService).scheduledRefresh(3L);

        verify(jobRunRecorder).start(PriceCheckScheduler.JOB_NAME);
        verify(jobRunRecorder, times(2))
                .recordItem(eq(RUN_ID), any(String.class), eq(JobStatus.SUCCESS), anyLong(), isNull());
        verify(jobRunRecorder)
                .recordItem(
                        eq(RUN_ID), eq("https://b.com/2"), eq(JobStatus.FAILED), anyLong(), contains("scraper down"));
        verify(jobRunRecorder).complete(eq(RUN_ID), eq(JobStatus.PARTIAL), eq(3), eq(2), eq(1), isNull());
    }

    @Test
    void refreshAll_recordItemFailureDoesNotMiscountSuccessfulWork() {
        Instant old = Instant.now().minusSeconds(60 * 60 * 24);
        when(trackedItemRepository.findStaleItems(any(Instant.class)))
                .thenReturn(List.of(new TrackedItemRefreshView(1L, "https://a.com/1", old)));
        doThrow(new RuntimeException("audit DB blip"))
                .when(jobRunRecorder)
                .recordItem(eq(RUN_ID), eq("https://a.com/1"), eq(JobStatus.SUCCESS), anyLong(), isNull());

        scheduler.refreshAll();

        verify(trackingService).scheduledRefresh(1L);
        verify(jobRunRecorder).complete(eq(RUN_ID), eq(JobStatus.SUCCESS), eq(1), eq(1), eq(0), isNull());
    }

    @Test
    void refreshAll_skipsNeverScrapableItems_notCountedAsFailed() {
        Instant old = Instant.now().minusSeconds(60 * 60 * 24);
        List<TrackedItemRefreshView> items = List.of(
                new TrackedItemRefreshView(1L, "https://ok.com/1", old),
                new TrackedItemRefreshView(2L, "https://www.amazon.com/2", old),
                new TrackedItemRefreshView(3L, "https://ok.com/3", old),
                new TrackedItemRefreshView(4L, "https://ivory.seed.invalid/item/1001", old));
        when(trackedItemRepository.findStaleItems(any(Instant.class))).thenReturn(items);
        // Explicit per-URL stubs (no lenient): every call the loop makes matches a stub, and all are used.
        when(urlValidator.isNeverScrapable("https://ok.com/1")).thenReturn(false);
        when(urlValidator.isNeverScrapable("https://www.amazon.com/2")).thenReturn(true);
        when(urlValidator.isNeverScrapable("https://ok.com/3")).thenReturn(false);
        when(urlValidator.isNeverScrapable("https://ivory.seed.invalid/item/1001"))
                .thenReturn(true);

        scheduler.refreshAll();

        // Skipped items are never refreshed (no request sent) and do NOT count as processed/failed.
        verify(trackingService).scheduledRefresh(1L);
        verify(trackingService).scheduledRefresh(3L);
        verify(trackingService, never()).scheduledRefresh(2L);
        verify(trackingService, never()).scheduledRefresh(4L);
        verify(jobRunRecorder, never()).recordItem(anyLong(), eq("https://www.amazon.com/2"), any(), anyLong(), any());
        verify(jobRunRecorder, never())
                .recordItem(anyLong(), eq("https://ivory.seed.invalid/item/1001"), any(), anyLong(), any());
        // processed = 2 (only the scrapable items), 0 failed.
        verify(jobRunRecorder).complete(eq(RUN_ID), eq(JobStatus.SUCCESS), eq(2), eq(2), eq(0), isNull());
    }

    @Test
    void refreshAll_emptyList_logsAndExits() {
        when(trackedItemRepository.findStaleItems(any(Instant.class))).thenReturn(List.of());

        scheduler.refreshAll();

        verifyNoInteractions(trackingService);

        verify(jobRunRecorder).start(PriceCheckScheduler.JOB_NAME);
        verify(jobRunRecorder, never()).recordItem(anyLong(), any(), any(), anyLong(), any());
        verify(jobRunRecorder).complete(eq(RUN_ID), eq(JobStatus.SUCCESS), eq(0), eq(0), eq(0), isNull());
    }
}
