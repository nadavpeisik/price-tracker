package com.np.pricehunt.backend.service.dashboard;

import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.DashboardAvailabilityResponse;
import com.np.pricehunt.backend.dto.DashboardBiggestDrop;
import com.np.pricehunt.backend.dto.DashboardFacets;
import com.np.pricehunt.backend.dto.DashboardListingRef;
import com.np.pricehunt.backend.dto.DashboardPageMeta;
import com.np.pricehunt.backend.dto.DashboardPricePointResponse;
import com.np.pricehunt.backend.dto.DashboardProductResponse;
import com.np.pricehunt.backend.dto.DashboardQueryRequest;
import com.np.pricehunt.backend.dto.DashboardResponse;
import com.np.pricehunt.backend.dto.DashboardSortKey;
import com.np.pricehunt.backend.dto.DashboardSummary;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.service.trend.PriceTrendService;
import com.np.pricehunt.backend.service.trend.ProductTrend;
import com.np.pricehunt.backend.util.ShopIdentity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Serves the tracked-items dashboard: one request, one response (issue #146).
 *
 * <p><b>Why filtering and sorting happen in Java rather than SQL.</b> The two summary tiles aggregate
 * over the <em>whole</em> tracked set regardless of the active query, so every product's name, shops
 * and delta are in memory before filtering even begins. Pushing an {@code ILIKE}/{@code EXISTS}
 * predicate into the database would add a third query over data already loaded, and two of the three
 * sort keys order by values no column holds — an FX-converted price and a computed delta. The
 * database work that <em>does</em> matter is the fetch, and that is where the two-cutoff query earns
 * its keep: it replaced a per-listing N+1 with a single bounded pass.
 *
 * <p><b>What this costs, and when to revisit.</b> Per-request cost scales with the size of the
 * tracked set, and nothing bounds that — the per-product listing cap shapes individual products, not
 * the catalogue (issue #172). The
 * documented escape hatch is a materialized per-product projection refreshed on write; it is
 * deliberately deferred until measurement says it is needed, because it trades a real correctness
 * property — everything computed from live data at one instant — for latency nobody has yet observed
 * as a problem.
 *
 * <p><b>One evaluation instant — but not one snapshot.</b> {@code asOf} is captured once and threaded
 * through both the lean whole-set pass and the page's sparklines, so a row's headline price cannot
 * describe a different moment than its own chart. That is the whole guarantee, and it is worth being
 * precise about what it excludes: the request runs at the default {@code READ_COMMITTED}, so each
 * statement sees its own database snapshot, and {@code PriceConverter} re-reads the live FX snapshot
 * per conversion. A concurrent scrape or a mid-request FX refresh can therefore still make one
 * response internally inconsistent — transient, self-corrected by the client's next poll, and not
 * worth pinning an immutable rate snapshot through the converter and calculator to prevent.
 *
 * <p><b>One read-only transaction spans the request, including the in-memory work</b> (review
 * finding, accepted as a documented tradeoff). The queries are not all up front — loading, snapshot
 * computation and the page's sparkline fetch interleave — so the connection is held across the
 * filtering and sorting between them. Three things make that the right call here rather than an
 * oversight: no network I/O happens inside, which is what the project's hard rule against long
 * transactions actually guards; at {@code READ_COMMITTED} the enclosing transaction buys no
 * cross-statement consistency to trade away, but {@code readOnly = true} does give Hibernate a
 * flush-free session for a whole-catalogue read; and splitting it would multiply connection
 * checkouts rather than shorten total hold time. {@code ProductQueryService} has the same shape. The
 * trigger to revisit is observable, not architectural: connection-pool wait time appearing under
 * concurrent dashboard load — at which point the projection escape hatch above removes most of the
 * in-transaction work anyway.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class DashboardQueryService {

    private final ProductRepository productRepository;
    private final TrackedItemRepository trackedItemRepository;
    private final DashboardSnapshotService snapshotService;
    private final PriceTrendService trendService;
    private final Clock clock;

    /**
     * @param request already normalized and validated by the controller, display currency included
     */
    public DashboardResponse query(DashboardQueryRequest request) {
        Instant asOf = clock.instant();
        String displayCurrency = request.displayCurrency();

        List<Product> products = productRepository.findAll();
        List<DashboardListingRef> listings = trackedItemRepository.findAllForDashboard();

        Map<Long, List<DashboardListingRef>> listingsByProductId = groupListingsByProductId(products, listings);
        Map<Long, ProductDashboardSnapshot> snapshotsByProductId =
                snapshotService.snapshotAll(listingsByProductId, asOf, displayCurrency);

        List<DashboardRow> allRows = products.stream()
                .map(product -> new DashboardRow(product, snapshotsByProductId.get(product.getId())))
                .toList();

        Map<Long, Set<String>> shopIdentitiesByProductId = collectShopIdentities(listingsByProductId);
        // Lower-cased once, not once per row: the needle is the same for every comparison.
        String caseFoldedSearchTerm =
                request.search() == null ? null : request.search().toLowerCase(Locale.ROOT);
        List<DashboardRow> matchingRows = allRows.stream()
                .filter(row -> matchesFilters(row, caseFoldedSearchTerm, request.shops(), shopIdentitiesByProductId))
                .sorted(comparator(request.sort()))
                .toList();

        List<DashboardRow> pageRows = selectPage(matchingRows, request.page(), request.size());
        Map<Long, ProductTrend> pageTrendsByProductId = loadPageTrends(pageRows, displayCurrency, asOf);

        return new DashboardResponse(
                pageRows.stream()
                        .map(row -> toResponse(row, displayCurrency, pageTrendsByProductId.get(row.productId())))
                        .toList(),
                new DashboardPageMeta(
                        request.page(),
                        request.size(),
                        matchingRows.size(),
                        totalPages(matchingRows.size(), request.size())),
                new DashboardFacets(facetLabels(listings)),
                summarize(allRows),
                summarize(matchingRows));
    }

    // --- loading ---

    private static Map<Long, List<DashboardListingRef>> groupListingsByProductId(
            List<Product> products, List<DashboardListingRef> listings) {

        // Seeded from the product list so a product with no listings still gets an entry, and any
        // listing whose product vanished between the two queries is dropped rather than inventing a row.
        Map<Long, List<DashboardListingRef>> listingsByProductId = new LinkedHashMap<>();
        products.forEach(product -> listingsByProductId.put(product.getId(), new ArrayList<>()));
        for (DashboardListingRef listing : listings) {
            List<DashboardListingRef> productListings = listingsByProductId.get(listing.productId());
            if (productListings != null) {
                productListings.add(listing);
            }
        }
        return listingsByProductId;
    }

    /**
     * Sparklines for the visible page only.
     *
     * <p>The series is the expensive part of the trend engine — a full multi-week window per listing —
     * and it is the one thing off-screen rows do not need. Skipped entirely on an empty page rather
     * than binding an empty {@code IN} list.
     */
    private Map<Long, ProductTrend> loadPageTrends(List<DashboardRow> pageRows, String displayCurrency, Instant asOf) {
        if (pageRows.isEmpty()) {
            return Map.of();
        }
        List<Long> productIds = pageRows.stream().map(DashboardRow::productId).toList();

        // Entities, not the light refs: computeProductTrends takes TrackedItem.
        Map<Long, List<TrackedItem>> listingsByProductId = trackedItemRepository.findByProductIdIn(productIds).stream()
                .collect(Collectors.groupingBy(item -> item.getProduct().getId()));
        productIds.forEach(id -> listingsByProductId.computeIfAbsent(id, key -> List.of()));

        return trendService.computeProductTrendsAsOf(listingsByProductId, null, displayCurrency, asOf);
    }

    // --- filtering ---

    private static Map<Long, Set<String>> collectShopIdentities(
            Map<Long, List<DashboardListingRef>> listingsByProductId) {
        Map<Long, Set<String>> identitiesByProductId = new HashMap<>();
        listingsByProductId.forEach((productId, listings) -> {
            Set<String> shopIdentities = new HashSet<>();
            for (DashboardListingRef listing : listings) {
                String shopIdentity = ShopIdentity.of(listing.shopName());
                if (shopIdentity != null) {
                    shopIdentities.add(shopIdentity);
                }
            }
            identitiesByProductId.put(productId, shopIdentities);
        });
        return identitiesByProductId;
    }

    /**
     * @param caseFoldedSearchTerm the search term already lower-cased, or null for "no search"
     * @param requestedShopIdentities folded shop identities; empty means "every shop"
     */
    private static boolean matchesFilters(
            DashboardRow row,
            String caseFoldedSearchTerm,
            List<String> requestedShopIdentities,
            Map<Long, Set<String>> shopIdentitiesByProductId) {

        if (caseFoldedSearchTerm != null && !nameContains(row.product().getName(), caseFoldedSearchTerm)) {
            return false;
        }
        if (requestedShopIdentities.isEmpty()) {
            return true;
        }
        // A product matches if ANY of its listings is at a selected shop — the chips filter products
        // by where they can be bought, they do not filter a product down to some of its shops.
        Set<String> productShopIdentities = shopIdentitiesByProductId.getOrDefault(row.productId(), Set.of());
        return requestedShopIdentities.stream().anyMatch(productShopIdentities::contains);
    }

    private static boolean nameContains(String name, String caseFoldedSearchTerm) {
        return name != null && name.toLowerCase(Locale.ROOT).contains(caseFoldedSearchTerm);
    }

    // --- sorting ---

    /**
     * Nulls sort last in every strategy, and every comparator ends on the product id.
     *
     * <p>Nulls last because a null price or delta means "we don't know", and unknowns belong below
     * real answers rather than winning "cheapest". The id tiebreak is what makes pagination stable:
     * without it, two products with the same name or the same delta could swap between page requests
     * and one of them would be shown twice while the other vanished.
     */
    private static Comparator<DashboardRow> comparator(DashboardSortKey sort) {
        Comparator<DashboardRow> primaryComparator =
                switch (sort) {
                    case NAME ->
                        Comparator.comparing(
                                row -> row.product().getName(), Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER));
                    case LOWEST_CURRENT_PRICE ->
                        Comparator.comparing(
                                DashboardRow::bestPriceConverted, Comparator.nullsLast(Comparator.naturalOrder()));
                    case BIGGEST_7D_DROP ->
                        Comparator.comparing(DashboardRow::delta7d, Comparator.nullsLast(Comparator.naturalOrder()));
                };
        return primaryComparator.thenComparing(DashboardRow::productId);
    }

    // --- pagination ---

    private static List<DashboardRow> selectPage(List<DashboardRow> rows, int page, int size) {
        // long arithmetic: a hand-typed ?page= near Integer.MAX_VALUE must produce an empty page, not
        // an overflowed negative index and a 500.
        long offset = (long) (page - 1) * size;
        if (offset >= rows.size()) {
            return List.of();
        }
        int fromIndex = (int) offset;
        return rows.subList(fromIndex, Math.min(fromIndex + size, rows.size()));
    }

    private static int totalPages(int totalElements, int size) {
        return (totalElements + size - 1) / size;
    }

    // --- facets ---

    /**
     * One label per distinct shop identity, rendered by its most frequent stored spelling.
     *
     * <p>Most frequent rather than first-seen or lower-cased: it never invents branding, and it is
     * stable across requests. Ties break on the exact string so the answer is deterministic. The list
     * is case-insensitively ordered because that is how a human scans a chip row.
     */
    private static List<String> facetLabels(List<DashboardListingRef> listings) {
        Map<String, Map<String, Integer>> spellingCountsByShopIdentity = new HashMap<>();
        for (DashboardListingRef listing : listings) {
            String shopIdentity = ShopIdentity.of(listing.shopName());
            if (shopIdentity == null) {
                continue;
            }
            spellingCountsByShopIdentity
                    .computeIfAbsent(shopIdentity, k -> new TreeMap<>())
                    .merge(listing.shopName().trim(), 1, Integer::sum);
        }

        return spellingCountsByShopIdentity.values().stream()
                .map(DashboardQueryService::mostFrequentSpelling)
                .sorted(String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder()))
                .toList();
    }

    private static String mostFrequentSpelling(Map<String, Integer> spellingCounts) {
        // The map is a TreeMap, so iteration is already in natural order — the first spelling to reach
        // a given count wins, which is exactly the documented tiebreak.
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> spelling : spellingCounts.entrySet()) {
            if (spelling.getValue() > bestCount) {
                best = spelling.getKey();
                bestCount = spelling.getValue();
            }
        }
        return best;
    }

    // --- summaries ---

    /**
     * Tiles over a set of rows: how many products, how many fell, and the largest fall.
     *
     * <p>{@code biggestDrop} considers <b>negative deltas only</b>. A catalogue where everything rose
     * must report no biggest drop at all — reporting the least-bad rise as the "biggest drop" would be
     * actively misleading. Ties go to the lowest product id so the tile does not flicker between equal
     * candidates on successive polls.
     */
    private static DashboardSummary summarize(List<DashboardRow> rows) {
        long dropCount = 0;
        DashboardRow biggestDropRow = null;
        for (DashboardRow row : rows) {
            BigDecimal delta = row.delta7d();
            if (delta == null || delta.signum() >= 0) {
                continue;
            }
            dropCount++;
            if (biggestDropRow == null || outranksForBiggestDrop(row, biggestDropRow)) {
                biggestDropRow = row;
            }
        }
        return new DashboardSummary(
                rows.size(),
                dropCount,
                biggestDropRow == null
                        ? null
                        : new DashboardBiggestDrop(
                                biggestDropRow.productId(),
                                biggestDropRow.product().getName(),
                                biggestDropRow.delta7d()));
    }

    private static boolean outranksForBiggestDrop(DashboardRow candidate, DashboardRow incumbent) {
        int byDelta = candidate.delta7d().compareTo(incumbent.delta7d());
        return byDelta < 0 || (byDelta == 0 && candidate.productId() < incumbent.productId());
    }

    // --- assembly ---

    private static DashboardProductResponse toResponse(DashboardRow row, String displayCurrency, ProductTrend trend) {
        ProductDashboardSnapshot snapshot = row.snapshot();
        boolean hasBestPrice = snapshot.bestPriceConverted() != null;
        return new DashboardProductResponse(
                row.productId(),
                row.product().getName(),
                null, // imageUrl — no column yet (#95)
                null, // category — no column yet
                toPlainDecimalString(snapshot.bestPriceConverted()),
                hasBestPrice ? displayCurrency : null,
                toPlainDecimalString(snapshot.bestPriceOriginal()),
                snapshot.bestPriceOriginalCurrency(),
                snapshot.bestPriceShop(),
                snapshot.conversionStale(),
                snapshot.conversionAsOf(),
                snapshot.mixedCurrencies(),
                new DashboardAvailabilityResponse(
                        snapshot.availability().status(),
                        snapshot.availability().availableCount(),
                        snapshot.availability().total()),
                snapshot.delta7d(),
                sparkline(trend));
    }

    private static List<DashboardPricePointResponse> sparkline(ProductTrend trend) {
        if (trend == null) {
            return List.of();
        }
        return trend.points().stream()
                .map(point -> new DashboardPricePointResponse(point.t(), toPlainDecimalString(point.price())))
                .toList();
    }

    /** Money leaves as a decimal string; {@code toPlainString} never emits scientific notation. */
    private static String toPlainDecimalString(BigDecimal amount) {
        return amount == null ? null : amount.toPlainString();
    }

    /**
     * A product paired with what the lean pass computed for it — the unit that gets filtered, sorted,
     * paged and finally rendered.
     *
     * <p>Sorting reads the snapshot rather than re-deriving anything, which is what guarantees
     * "cheapest first" orders by the same number the row displays.
     */
    private record DashboardRow(Product product, ProductDashboardSnapshot snapshot) {

        Long productId() {
            return product.getId();
        }

        BigDecimal bestPriceConverted() {
            return snapshot.bestPriceConverted();
        }

        BigDecimal delta7d() {
            return snapshot.delta7d();
        }
    }
}
