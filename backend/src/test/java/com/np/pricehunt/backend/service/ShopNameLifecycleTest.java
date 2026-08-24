package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.np.pricehunt.backend.domain.MappingOrigin;
import com.np.pricehunt.backend.domain.ShopNameSource;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.service.ShopNameResolver.Resolved;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

// The shop-name lifecycle's choreography: the curated short-circuit, the strong-only learn gate, and
// best-effort failure handling. DB semantics of resolve/apply/upsert are covered by
// ShopNameResolverTest / TrackedItemRepositoryTest / ShopNameMappingRepositoryTest.
@ExtendWith(MockitoExtension.class)
class ShopNameLifecycleTest {

    private static final String URL = "https://thomannmusic.com/x.htm";
    private static final Long ITEM_ID = 1L;

    @Mock
    private ShopNameResolver resolver;

    @Mock
    private TrackedItemRepository trackedItemRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private ShopNameLifecycle lifecycle;

    @BeforeEach
    void setUp() {
        lifecycle = new ShopNameLifecycle(resolver, trackedItemRepository, transactionTemplate);
    }

    private void runFloorTxInline() {
        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
    }

    @SuppressWarnings("unchecked")
    private void runRefineTxInline() {
        doAnswer(inv -> {
                    ((Consumer<TransactionStatus>) inv.getArgument(0)).accept(null);
                    return null;
                })
                .when(transactionTemplate)
                .executeWithoutResult(any());
    }

    private static ScrapeResponse.ShopNameProposal proposal(String name, boolean strong) {
        return new ScrapeResponse.ShopNameProposal(name, strong);
    }

    // --- establishFloor ---

    @Test
    void establishFloor_appliesResolvedName_andReportsCurated() {
        runFloorTxInline();
        when(resolver.resolve(URL, null))
                .thenReturn(new Resolved("Amazon", ShopNameSource.MAPPING, MappingOrigin.CURATED));

        assertThat(lifecycle.establishFloor(ITEM_ID, URL)).isTrue();

        verify(trackedItemRepository).applyShopName(ITEM_ID, "Amazon", ShopNameSource.MAPPING);
    }

    @Test
    void establishFloor_hostFallback_isNotCurated() {
        runFloorTxInline();
        when(resolver.resolve(URL, null))
                .thenReturn(new Resolved("thomannmusic.com", ShopNameSource.HOST_FALLBACK, null));

        assertThat(lifecycle.establishFloor(ITEM_ID, URL)).isFalse();

        verify(trackedItemRepository).applyShopName(ITEM_ID, "thomannmusic.com", ShopNameSource.HOST_FALLBACK);
    }

    @Test
    void establishFloor_failure_isSwallowed_andNotCurated() {
        runFloorTxInline();
        when(resolver.resolve(any(), any())).thenThrow(new RuntimeException("name DB down"));

        assertThat(lifecycle.establishFloor(ITEM_ID, URL)).isFalse(); // must not throw
    }

    // --- refineFromScrape ---

    @Test
    void refineFromScrape_curatedFloor_shortCircuits() {
        // Even a strong page proposal never touches a curated name: no learn, no resolve, no write.
        lifecycle.refineFromScrape(ITEM_ID, URL, proposal("Strong Site", true), true);

        verifyNoInteractions(resolver, trackedItemRepository, transactionTemplate);
    }

    @Test
    void refineFromScrape_noProposal_isNoOp() {
        lifecycle.refineFromScrape(ITEM_ID, URL, null, false);
        lifecycle.refineFromScrape(ITEM_ID, URL, proposal("   ", true), false);

        verifyNoInteractions(resolver, trackedItemRepository, transactionTemplate);
    }

    @Test
    void refineFromScrape_strongProposal_isLearnedThenApplied() {
        runRefineTxInline();
        when(resolver.resolve(URL, "Musikhaus Thomann"))
                .thenReturn(new Resolved("Musikhaus Thomann", ShopNameSource.MAPPING, MappingOrigin.LEARNED));

        lifecycle.refineFromScrape(ITEM_ID, URL, proposal("Musikhaus Thomann", true), false);

        var order = inOrder(resolver, trackedItemRepository);
        order.verify(resolver).learn(eq(URL), eq("Musikhaus Thomann"));
        order.verify(resolver).resolve(URL, "Musikhaus Thomann");
        order.verify(trackedItemRepository).applyShopName(ITEM_ID, "Musikhaus Thomann", ShopNameSource.MAPPING);
    }

    @Test
    void refineFromScrape_weakProposal_isAppliedButNeverLearned() {
        runRefineTxInline();
        when(resolver.resolve(URL, "Some Title")).thenReturn(new Resolved("Some Title", ShopNameSource.DETECTED, null));

        lifecycle.refineFromScrape(ITEM_ID, URL, proposal("Some Title", false), false);

        verify(resolver, never()).learn(any(), any());
        verify(trackedItemRepository).applyShopName(ITEM_ID, "Some Title", ShopNameSource.DETECTED);
    }

    @Test
    void refineFromScrape_failure_isSwallowed() {
        doThrow(new RuntimeException("mapping upsert failed")).when(resolver).learn(any(), any());

        lifecycle.refineFromScrape(ITEM_ID, URL, proposal("Musikhaus Thomann", true), false); // must not throw

        verify(trackedItemRepository, never()).applyShopName(any(), any(), any());
    }
}
