package com.np.pricehunt.backend.service.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.dto.AvailabilityRollupStatus;
import com.np.pricehunt.backend.dto.DashboardProductResponse;
import com.np.pricehunt.backend.dto.DashboardQueryRequest;
import com.np.pricehunt.backend.dto.DashboardResponse;
import com.np.pricehunt.backend.dto.DashboardSortKey;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.repository.projection.DashboardListingRef;
import com.np.pricehunt.backend.service.dashboard.ProductDashboardSnapshot.AvailabilitySummary;
import com.np.pricehunt.backend.service.trend.BestOffer;
import com.np.pricehunt.backend.service.trend.PriceTrendService;
import com.np.pricehunt.backend.service.trend.ProductTrend;
import com.np.pricehunt.backend.service.trend.TrendPoint;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Filtering, sorting, paging, facets and summaries — the query orchestration, with the lean pass and
 * the trend engine stubbed. What each of those computes is covered by their own suites; what this
 * pins is how their answers are combined into one envelope.
 */
@ExtendWith(MockitoExtension.class)
class DashboardQueryServiceTest {

    private static final Instant NOW = Instant.parse("2026-03-20T12:00:00Z");
    private static final String ILS = "ILS";

    @Mock
    private ProductRepository productRepository;

    @Mock
    private TrackedItemRepository trackedItemRepository;

    @Mock
    private DashboardSnapshotService snapshotService;

    @Mock
    private PriceTrendService trendService;

    private DashboardQueryService service;

    /** Fixtures accumulate here and are installed by {@link #stubCatalogue()}. */
    private final List<Product> products = new ArrayList<>();

    private final List<DashboardListingRef> listings = new ArrayList<>();
    private final Map<Long, ProductDashboardSnapshot> snapshots = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new DashboardQueryService(
                productRepository,
                trackedItemRepository,
                snapshotService,
                trendService,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    // --- filtering ---

    @Test
    void searchMatchesProductNameCaseInsensitively_asASubstring() {
        product(1L, "Sony WH-1000XM5");
        product(2L, "Keychron K8 Pro");
        stubCatalogue();

        assertThat(names(query(request("sony", List.of(), DashboardSortKey.NAME, 1, 20))))
                .containsExactly("Sony WH-1000XM5");
        assertThat(names(query(request("CHRON", List.of(), DashboardSortKey.NAME, 1, 20))))
                .containsExactly("Keychron K8 Pro");
    }

    @Test
    void shopFilterMatchesAProductWithAnyListingAtThatShop() {
        // The chips filter which products are shown, not which of a product's shops are shown.
        product(1L, "Sony");
        product(2L, "Keychron");
        listing(10L, 1L, "Amazon");
        listing(11L, 1L, "KSP");
        listing(20L, 2L, "KSP");
        stubCatalogue();

        assertThat(names(query(request(null, List.of("amazon"), DashboardSortKey.NAME, 1, 20))))
                .containsExactly("Sony");
        assertThat(names(query(request(null, List.of("ksp"), DashboardSortKey.NAME, 1, 20))))
                .containsExactly("Keychron", "Sony");
    }

    @Test
    void shopFilterMatchesRegardlessOfHowTheNameWasStored() {
        // The whole point of folding: a chip derived from "Amazon" must select a listing stored as
        // "amazon", and vice versa, or half that shop's listings become unreachable.
        product(1L, "Sony");
        product(2L, "Keychron");
        listing(10L, 1L, "Amazon");
        listing(20L, 2L, "amazon");
        stubCatalogue();

        assertThat(names(query(request(null, List.of("amazon"), DashboardSortKey.NAME, 1, 20))))
                .containsExactly("Keychron", "Sony");
    }

    @Test
    void listingWithoutAShopNameNeverMatchesAFilter() {
        product(1L, "Sony");
        listing(10L, 1L, null);
        listing(11L, 1L, "   ");
        stubCatalogue();

        assertThat(names(query(request(null, List.of("amazon"), DashboardSortKey.NAME, 1, 20))))
                .isEmpty();
    }

    // --- sorting ---

    @Test
    void sortByName_isCaseInsensitive() {
        product(1L, "zebra");
        product(2L, "Apple");
        stubCatalogue();

        assertThat(names(query(request(null, List.of(), DashboardSortKey.NAME, 1, 20))))
                .containsExactly("Apple", "zebra");
    }

    @Test
    void sortByLowestPrice_putsUnpricedProductsLast() {
        product(1L, "Expensive").priced("300");
        product(2L, "Unpriced");
        product(3L, "Cheap").priced("100");
        stubCatalogue();

        assertThat(names(query(request(null, List.of(), DashboardSortKey.LOWEST_CURRENT_PRICE, 1, 20))))
                .containsExactly("Cheap", "Expensive", "Unpriced");
    }

    @Test
    void sortByBiggestDrop_putsTheMostNegativeFirstAndNullsLast() {
        product(1L, "Steady").delta("0.00");
        product(2L, "New");
        product(3L, "Plunged").delta("-30.00");
        product(4L, "Dipped").delta("-5.00");
        stubCatalogue();

        assertThat(names(query(request(null, List.of(), DashboardSortKey.BIGGEST_7D_DROP, 1, 20))))
                .containsExactly("Plunged", "Dipped", "Steady", "New");
    }

    @Test
    void tiedSortKeys_breakOnProductId_soPagingCannotDuplicateOrDropARow() {
        product(3L, "Same").priced("100");
        product(1L, "Same").priced("100");
        product(2L, "Same").priced("100");
        stubCatalogue();

        assertThat(ids(query(request(null, List.of(), DashboardSortKey.LOWEST_CURRENT_PRICE, 1, 20))))
                .containsExactly(1L, 2L, 3L);
    }

    // --- pagination ---

    @Test
    void firstPageIsPageOne_andTheResponseEchoesIt() {
        product(1L, "A");
        product(2L, "B");
        product(3L, "C");
        stubCatalogue();

        DashboardResponse response = query(request(null, List.of(), DashboardSortKey.NAME, 1, 2));

        assertThat(names(response)).containsExactly("A", "B");
        assertThat(response.page().number()).isEqualTo(1);
        assertThat(response.page().totalElements()).isEqualTo(3);
        assertThat(response.page().totalPages()).isEqualTo(2);
    }

    @Test
    void secondPageContinuesWhereTheFirstEnded() {
        product(1L, "A");
        product(2L, "B");
        product(3L, "C");
        stubCatalogue();

        DashboardResponse response = query(request(null, List.of(), DashboardSortKey.NAME, 2, 2));

        assertThat(names(response)).containsExactly("C");
        assertThat(response.page().number()).isEqualTo(2);
    }

    @Test
    void overflowPageIsEmptyWithTruthfulTotals_notAnError() {
        product(1L, "A");
        stubCatalogue();

        DashboardResponse response = query(request(null, List.of(), DashboardSortKey.NAME, 9, 20));

        assertThat(response.items()).isEmpty();
        assertThat(response.page().totalElements()).isEqualTo(1);
        assertThat(response.page().totalPages()).isEqualTo(1);
        // No sparkline query for a page with nothing on it.
        verify(trendService, never()).computeProductTrendsAsOf(any(), any(), anyString(), any());
    }

    @Test
    void extremePageNumberDoesNotOverflowIntoANegativeSlice() {
        product(1L, "A");
        stubCatalogue();

        DashboardResponse response = query(request(null, List.of(), DashboardSortKey.NAME, Integer.MAX_VALUE, 20));

        assertThat(response.items()).isEmpty();
        assertThat(response.page().number()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void emptyCatalogueReportsZeroPages() {
        stubCatalogue();

        DashboardResponse response = query(request(null, List.of(), DashboardSortKey.NAME, 1, 20));

        assertThat(response.items()).isEmpty();
        assertThat(response.page().totalPages()).isZero();
        assertThat(response.page().totalElements()).isZero();
    }

    // --- facets ---

    @Test
    void facetsAreGlobal_notDerivedFromTheFilteredPage() {
        // Filtering to one shop must not erase the other chips, or the filter would be a one-way door.
        product(1L, "Sony");
        product(2L, "Keychron");
        listing(10L, 1L, "Amazon");
        listing(20L, 2L, "KSP");
        stubCatalogue();

        DashboardResponse response = query(request(null, List.of("amazon"), DashboardSortKey.NAME, 1, 20));

        assertThat(response.items()).hasSize(1);
        assertThat(response.facets().shops()).containsExactly("Amazon", "KSP");
    }

    @Test
    void caseVariantsCollapseToOneChip_labelledByTheMostCommonSpelling() {
        product(1L, "Sony");
        listing(10L, 1L, "amazon");
        listing(11L, 1L, "Amazon");
        listing(12L, 1L, "Amazon");
        stubCatalogue();

        assertThat(query(request(null, List.of(), DashboardSortKey.NAME, 1, 20))
                        .facets()
                        .shops())
                .containsExactly("Amazon");
    }

    @Test
    void tiedSpellings_breakOnNaturalOrderSoTheChipDoesNotFlicker() {
        product(1L, "Sony");
        listing(10L, 1L, "amazon");
        listing(11L, 1L, "Amazon");
        stubCatalogue();

        // 'A' (65) sorts before 'a' (97) naturally.
        assertThat(query(request(null, List.of(), DashboardSortKey.NAME, 1, 20))
                        .facets()
                        .shops())
                .containsExactly("Amazon");
    }

    @Test
    void facetsExcludeListingsWithNoShopName() {
        product(1L, "Sony");
        listing(10L, 1L, null);
        listing(11L, 1L, "  ");
        listing(12L, 1L, "KSP");
        stubCatalogue();

        assertThat(query(request(null, List.of(), DashboardSortKey.NAME, 1, 20))
                        .facets()
                        .shops())
                .containsExactly("KSP");
    }

    @Test
    void facetsAreOrderedForAHumanScanningTheChipRow() {
        product(1L, "Sony");
        listing(10L, 1L, "zShop");
        listing(11L, 1L, "Amazon");
        listing(12L, 1L, "ksp");
        stubCatalogue();

        assertThat(query(request(null, List.of(), DashboardSortKey.NAME, 1, 20))
                        .facets()
                        .shops())
                .containsExactly("Amazon", "ksp", "zShop");
    }

    // --- summaries ---

    @Test
    void globalSummaryCoversEverything_whileTheQuerySummaryFollowsTheFilter() {
        product(1L, "Sony").delta("-10.00");
        product(2L, "Keychron").delta("-20.00");
        stubCatalogue();

        DashboardResponse response = query(request("sony", List.of(), DashboardSortKey.NAME, 1, 20));

        assertThat(response.globalSummary().totalTracked()).isEqualTo(2);
        assertThat(response.globalSummary().drops7d()).isEqualTo(2);
        assertThat(response.globalSummary().biggestDrop().productName()).isEqualTo("Keychron");

        assertThat(response.summaryForCurrentQuery().totalTracked()).isEqualTo(1);
        assertThat(response.summaryForCurrentQuery().drops7d()).isEqualTo(1);
        assertThat(response.summaryForCurrentQuery().biggestDrop().productName())
                .isEqualTo("Sony");
    }

    @Test
    void summaryCountsFallsOnly_neverAFlatDeltaOrAMissingOne() {
        product(1L, "Rose").delta("12.00");
        product(2L, "Steady").delta("0.00");
        product(3L, "New");
        product(4L, "Fell").delta("-1.00");
        stubCatalogue();

        assertThat(query(request(null, List.of(), DashboardSortKey.NAME, 1, 20))
                        .globalSummary()
                        .drops7d())
                .isEqualTo(1);
    }

    @Test
    void nothingFell_meansNoBiggestDrop_notTheSmallestRise() {
        product(1L, "Rose").delta("3.00");
        product(2L, "Steady").delta("0.00");
        stubCatalogue();

        assertThat(query(request(null, List.of(), DashboardSortKey.NAME, 1, 20))
                        .globalSummary()
                        .biggestDrop())
                .isNull();
    }

    @Test
    void tiedBiggestDrops_resolveToTheLowestProductId() {
        product(3L, "Third").delta("-15.00");
        product(1L, "First").delta("-15.00");
        stubCatalogue();

        assertThat(query(request(null, List.of(), DashboardSortKey.NAME, 1, 20))
                        .globalSummary()
                        .biggestDrop()
                        .productId())
                .isEqualTo(1L);
    }

    @Test
    void summaryPrecedesPagination_soTilesDoNotChangeAsYouPage() {
        product(1L, "A").delta("-1.00");
        product(2L, "B").delta("-2.00");
        product(3L, "C").delta("-3.00");
        stubCatalogue();

        DashboardResponse response = query(request(null, List.of(), DashboardSortKey.NAME, 1, 1));

        assertThat(response.items()).hasSize(1);
        assertThat(response.summaryForCurrentQuery().totalTracked()).isEqualTo(3);
        assertThat(response.summaryForCurrentQuery().drops7d()).isEqualTo(3);
    }

    // --- row assembly ---

    @Test
    void rowCarriesMoneyAsDecimalStringsAndTheDeltaAsANumber() {
        product(1L, "Sony").priced("363.6364").delta("-8.25");
        stubCatalogue();

        DashboardProductResponse row = query(request(null, List.of(), DashboardSortKey.NAME, 1, 20))
                .items()
                .get(0);

        assertThat(row.bestPriceConverted()).isEqualTo("363.6364");
        assertThat(row.bestPriceConvertedCurrency()).isEqualTo(ILS);
        assertThat(row.bestPriceOriginal()).isEqualTo("100.0000");
        assertThat(row.bestPriceOriginalCurrency()).isEqualTo("USD");
        assertThat(row.bestPriceShop()).isEqualTo("Amazon");
        assertThat(row.delta7d()).isEqualByComparingTo("-8.25");
        assertThat(row.availability().status()).isEqualTo(AvailabilityRollupStatus.AVAILABLE);
    }

    @Test
    void unpricedRowNullsTheCurrencyToo_soTheUiNeverRendersALoneSymbol() {
        product(1L, "Sony");
        stubCatalogue();

        DashboardProductResponse row = query(request(null, List.of(), DashboardSortKey.NAME, 1, 20))
                .items()
                .get(0);

        assertThat(row.bestPriceConverted()).isNull();
        assertThat(row.bestPriceConvertedCurrency()).isNull();
        assertThat(row.bestPriceOriginal()).isNull();
    }

    @Test
    void sparklineKeepsTheShapeAndDropsTheWinningListing() {
        product(1L, "Sony");
        stubCatalogue();
        Instant day = NOW.minusSeconds(86_400);
        when(trendService.computeProductTrendsAsOf(any(), any(), anyString(), any()))
                .thenReturn(Map.of(
                        1L,
                        new ProductTrend(
                                List.of(new TrendPoint(day, new BigDecimal("99.5000"), new BestOffer(10L, "KSP", day))),
                                null,
                                null,
                                false)));

        DashboardProductResponse row = query(request(null, List.of(), DashboardSortKey.NAME, 1, 20))
                .items()
                .get(0);

        assertThat(row.sparkline()).singleElement().satisfies(point -> {
            assertThat(point.t()).isEqualTo(day);
            assertThat(point.price()).isEqualTo("99.5000");
        });
    }

    @Test
    void everythingIsEvaluatedAtOneInstant() {
        // The lean pass and the sparklines must describe the same moment, or a row's headline price
        // could disagree with its own chart across a UTC midnight.
        product(1L, "Sony");
        stubCatalogue();

        query(request(null, List.of(), DashboardSortKey.NAME, 1, 20));

        verify(snapshotService).snapshotAll(any(), org.mockito.ArgumentMatchers.eq(NOW), anyString());
        verify(trendService)
                .computeProductTrendsAsOf(
                        any(),
                        org.mockito.ArgumentMatchers.isNull(),
                        anyString(),
                        org.mockito.ArgumentMatchers.eq(NOW));
    }

    @Test
    void requestCopiesTheShopListSoACallerCannotMutateItAfterwards() {
        // The record's javadoc promises immutability; without the copy that is only convention, and a
        // filter could change under the service mid-request.
        List<String> mutable = new ArrayList<>(List.of("amazon"));
        DashboardQueryRequest request = new DashboardQueryRequest(null, mutable, DashboardSortKey.NAME, 1, 20, ILS);

        mutable.add("ksp");

        assertThat(request.shops()).containsExactly("amazon");
    }

    // --- fixtures ---

    private DashboardResponse query(DashboardQueryRequest request) {
        return service.query(request);
    }

    private static DashboardQueryRequest request(
            String search, List<String> shops, DashboardSortKey sort, int page, int size) {
        return new DashboardQueryRequest(search, shops, sort, page, size, ILS);
    }

    private static List<String> names(DashboardResponse response) {
        return response.items().stream().map(DashboardProductResponse::name).toList();
    }

    private static List<Long> ids(DashboardResponse response) {
        return response.items().stream().map(DashboardProductResponse::id).toList();
    }

    private SnapshotBuilder product(long id, String name) {
        products.add(Product.builder().id(id).name(name).build());
        SnapshotBuilder builder = new SnapshotBuilder(id);
        snapshots.put(id, builder.build());
        return builder;
    }

    private void listing(long trackedItemId, long productId, String shopName) {
        listings.add(new DashboardListingRef(trackedItemId, productId, shopName));
    }

    private void stubCatalogue() {
        when(productRepository.findAll()).thenReturn(List.copyOf(products));
        when(trackedItemRepository.findAllForDashboard()).thenReturn(List.copyOf(listings));
        when(snapshotService.snapshotAll(any(), any(), anyString())).thenReturn(Map.copyOf(snapshots));
        // Sparklines are deliberately NOT stubbed here: Mockito's defaults already return an empty
        // list and an empty map, which is the right "no series" answer for most cases, and stubbing
        // them for the tests that page past the end would trip strict-stubs.
    }

    /** Mutates the snapshot already registered for a product, so fixtures read as one fluent line. */
    private final class SnapshotBuilder {

        private final long productId;
        private BigDecimal bestPriceConverted;
        private BigDecimal delta7d;

        private SnapshotBuilder(long productId) {
            this.productId = productId;
        }

        SnapshotBuilder priced(String converted) {
            this.bestPriceConverted = new BigDecimal(converted);
            snapshots.put(productId, build());
            return this;
        }

        SnapshotBuilder delta(String delta) {
            this.delta7d = new BigDecimal(delta);
            snapshots.put(productId, build());
            return this;
        }

        private ProductDashboardSnapshot build() {
            boolean priced = bestPriceConverted != null;
            return new ProductDashboardSnapshot(
                    productId,
                    bestPriceConverted,
                    priced ? new BigDecimal("100.0000") : null,
                    priced ? "USD" : null,
                    priced ? "Amazon" : null,
                    priced ? LocalDate.of(2026, 3, 20) : null,
                    false,
                    false,
                    new AvailabilitySummary(AvailabilityRollupStatus.AVAILABLE, 1, 1),
                    delta7d);
        }
    }
}
