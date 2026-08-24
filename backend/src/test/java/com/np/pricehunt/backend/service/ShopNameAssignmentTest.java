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

// The shop-name assignment's choreography: the strong-only learn gate and best-effort failure
// handling. The "curated wins" gate is the pipeline's (ProductTrackingServiceShopNameTest). DB semantics of
// resolve/apply/upsert are covered by
// ShopNameResolverTest / TrackedItemRepositoryTest / ShopNameMappingRepositoryTest.
@ExtendWith(MockitoExtension.class)
class ShopNameAssignmentTest {

    private static final String URL = "https://thomannmusic.com/x.htm";
    private static final Long ITEM_ID = 1L;

    @Mock
    private ShopNameResolver resolver;

    @Mock
    private TrackedItemRepository trackedItemRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    private ShopNameAssignment assignment;

    @BeforeEach
    void setUp() {
        assignment = new ShopNameAssignment(resolver, trackedItemRepository, transactionTemplate);
    }

    private void runUrlTxInline() {
        when(transactionTemplate.execute(any()))
                .thenAnswer(inv -> ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null));
    }

    @SuppressWarnings("unchecked")
    private void runPageTxInline() {
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

    // --- applyNameFromUrl ---

    @Test
    void applyNameFromUrl_appliesResolvedName_andReportsCurated() {
        runUrlTxInline();
        when(resolver.resolve(URL, null))
                .thenReturn(new Resolved("Amazon", ShopNameSource.MAPPING, MappingOrigin.CURATED));

        assertThat(assignment.applyNameFromUrl(ITEM_ID, URL)).isTrue();

        verify(trackedItemRepository).applyShopName(ITEM_ID, "Amazon", ShopNameSource.MAPPING);
    }

    @Test
    void applyNameFromUrl_hostFallback_isNotCurated() {
        runUrlTxInline();
        when(resolver.resolve(URL, null))
                .thenReturn(new Resolved("thomannmusic.com", ShopNameSource.HOST_FALLBACK, null));

        assertThat(assignment.applyNameFromUrl(ITEM_ID, URL)).isFalse();

        verify(trackedItemRepository).applyShopName(ITEM_ID, "thomannmusic.com", ShopNameSource.HOST_FALLBACK);
    }

    @Test
    void applyNameFromUrl_failure_isSwallowed_andNotCurated() {
        runUrlTxInline();
        when(resolver.resolve(any(), any())).thenThrow(new RuntimeException("name DB down"));

        assertThat(assignment.applyNameFromUrl(ITEM_ID, URL)).isFalse(); // must not throw
    }

    // --- applyNameFromPage ---

    @Test
    void applyNameFromPage_noProposal_isNoOp() {
        assignment.applyNameFromPage(ITEM_ID, URL, null);
        assignment.applyNameFromPage(ITEM_ID, URL, proposal("   ", true));

        verifyNoInteractions(resolver, trackedItemRepository, transactionTemplate);
    }

    @Test
    void applyNameFromPage_strongProposal_isLearnedThenApplied() {
        runPageTxInline();
        when(resolver.resolve(URL, "Musikhaus Thomann"))
                .thenReturn(new Resolved("Musikhaus Thomann", ShopNameSource.MAPPING, MappingOrigin.LEARNED));

        assignment.applyNameFromPage(ITEM_ID, URL, proposal("Musikhaus Thomann", true));

        var order = inOrder(resolver, trackedItemRepository);
        order.verify(resolver).learn(eq(URL), eq("Musikhaus Thomann"));
        order.verify(resolver).resolve(URL, "Musikhaus Thomann");
        order.verify(trackedItemRepository).applyShopName(ITEM_ID, "Musikhaus Thomann", ShopNameSource.MAPPING);
    }

    @Test
    void applyNameFromPage_weakProposal_isAppliedButNeverLearned() {
        runPageTxInline();
        when(resolver.resolve(URL, "Some Title")).thenReturn(new Resolved("Some Title", ShopNameSource.DETECTED, null));

        assignment.applyNameFromPage(ITEM_ID, URL, proposal("Some Title", false));

        verify(resolver, never()).learn(any(), any());
        verify(trackedItemRepository).applyShopName(ITEM_ID, "Some Title", ShopNameSource.DETECTED);
    }

    @Test
    void applyNameFromPage_failure_isSwallowed() {
        doThrow(new RuntimeException("mapping upsert failed")).when(resolver).learn(any(), any());

        assignment.applyNameFromPage(ITEM_ID, URL, proposal("Musikhaus Thomann", true)); // must not throw

        verify(trackedItemRepository, never()).applyShopName(any(), any(), any());
    }
}
