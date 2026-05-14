package com.np.pricehunt.backend.scheduler;

import com.np.pricehunt.backend.dto.TrackedItemRefreshView;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.service.ProductTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PriceCheckSchedulerTest {

    private static final long SIX_HOURS_MS = 21_600_000L;

    @Mock private ProductTrackingService trackingService;
    @Mock private TrackedItemRepository trackedItemRepository;

    private PriceCheckScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new PriceCheckScheduler(trackingService, trackedItemRepository, SIX_HOURS_MS);
    }

    @Test
    void refreshAll_callsScheduledRefreshForEachItem() {
        Instant old = Instant.now().minusSeconds(60 * 60 * 24);
        List<TrackedItemRefreshView> items = List.of(
                new TrackedItemRefreshView(1L, "https://a.com/1", old),
                new TrackedItemRefreshView(2L, "https://b.com/2", old),
                new TrackedItemRefreshView(3L, "https://c.com/3", null)
        );
        when(trackedItemRepository.findStaleItems(any(Instant.class))).thenReturn(items);

        scheduler.refreshAll();

        verify(trackingService).scheduledRefresh(1L);
        verify(trackingService).scheduledRefresh(2L);
        verify(trackingService).scheduledRefresh(3L);
        verifyNoMoreInteractions(trackingService);
    }

    @Test
    void refreshAll_singleItemFailureDoesNotStopOthers() {
        Instant old = Instant.now().minusSeconds(60 * 60 * 24);
        List<TrackedItemRefreshView> items = List.of(
                new TrackedItemRefreshView(1L, "https://a.com/1", old),
                new TrackedItemRefreshView(2L, "https://b.com/2", old),
                new TrackedItemRefreshView(3L, "https://c.com/3", old)
        );
        when(trackedItemRepository.findStaleItems(any(Instant.class))).thenReturn(items);
        when(trackingService.scheduledRefresh(1L)).thenReturn(null);
        when(trackingService.scheduledRefresh(3L)).thenReturn(null);
        when(trackingService.scheduledRefresh(2L)).thenThrow(new RuntimeException("scraper down"));

        scheduler.refreshAll();

        verify(trackingService).scheduledRefresh(1L);
        verify(trackingService).scheduledRefresh(2L);
        verify(trackingService).scheduledRefresh(3L);
    }

    @Test
    void refreshAll_emptyList_logsAndExits() {
        when(trackedItemRepository.findStaleItems(any(Instant.class))).thenReturn(List.of());

        scheduler.refreshAll();

        verifyNoInteractions(trackingService);
    }
}
