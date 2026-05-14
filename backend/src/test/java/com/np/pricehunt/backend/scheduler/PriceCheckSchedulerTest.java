package com.np.pricehunt.backend.scheduler;

import com.np.pricehunt.backend.dto.TrackedItemRefreshView;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.service.ProductTrackingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PriceCheckSchedulerTest {

    private static final long SIX_HOURS_MS = 21_600_000L;

    @Mock private ProductTrackingService trackingService;
    @Mock private TrackedItemRepository trackedItemRepository;

    @InjectMocks private PriceCheckScheduler scheduler;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(scheduler, "fixedDelayMs", SIX_HOURS_MS);
    }

    @Test
    void refreshAll_callsScheduledRefreshForEachItem() {
        Instant old = Instant.now().minusSeconds(60 * 60 * 24);
        List<TrackedItemRefreshView> items = List.of(
                new TrackedItemRefreshView(1L, "https://a.com/1", old),
                new TrackedItemRefreshView(2L, "https://b.com/2", old),
                new TrackedItemRefreshView(3L, "https://c.com/3", null)
        );
        when(trackedItemRepository.findAllForRefresh()).thenReturn(items);

        scheduler.refreshAll();

        verify(trackingService).scheduledRefresh(1L);
        verify(trackingService).scheduledRefresh(2L);
        verify(trackingService).scheduledRefresh(3L);
    }

    @Test
    void refreshAll_singleItemFailureDoesNotStopOthers() {
        Instant old = Instant.now().minusSeconds(60 * 60 * 24);
        List<TrackedItemRefreshView> items = List.of(
                new TrackedItemRefreshView(1L, "https://a.com/1", old),
                new TrackedItemRefreshView(2L, "https://b.com/2", old),
                new TrackedItemRefreshView(3L, "https://c.com/3", old)
        );
        when(trackedItemRepository.findAllForRefresh()).thenReturn(items);
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
        when(trackedItemRepository.findAllForRefresh()).thenReturn(List.of());

        scheduler.refreshAll();

        verifyNoInteractions(trackingService);
    }

    @Test
    void refreshAll_skipsItemRefreshedWithinDelayWindow() {
        Instant fresh = Instant.now().minusSeconds(60);
        Instant old = Instant.now().minusSeconds(60 * 60 * 24);
        List<TrackedItemRefreshView> items = List.of(
                new TrackedItemRefreshView(1L, "https://a.com/1", fresh),
                new TrackedItemRefreshView(2L, "https://b.com/2", old)
        );
        when(trackedItemRepository.findAllForRefresh()).thenReturn(items);

        scheduler.refreshAll();

        verify(trackingService, never()).scheduledRefresh(1L);
        verify(trackingService).scheduledRefresh(2L);
    }
}
