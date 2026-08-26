package com.np.pricehunt.backend.controller;

import com.np.pricehunt.backend.config.DashboardProperties;
import com.np.pricehunt.backend.dto.DashboardQueryRequest;
import com.np.pricehunt.backend.dto.DashboardResponse;
import com.np.pricehunt.backend.dto.DashboardSortKey;
import com.np.pricehunt.backend.exception.ValidationException;
import com.np.pricehunt.backend.service.dashboard.DashboardQueryService;
import com.np.pricehunt.backend.util.ShopIdentity;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The tracked-items dashboard's single endpoint (issue #146).
 *
 * <p><b>Pagination is 1-based on both boundaries.</b> {@code ?page=1} is the first page and the
 * response echoes that number back, so any page number the client receives is a page number it can
 * send. Spring Data's 0-based convention is an internal detail of that library, not a REST contract;
 * mixing the two once meant the response's own page number silently addressed the next page.
 *
 * <p><b>Why no {@code Pageable}.</b> Three reasons, none of them stylistic. The response envelope
 * carries facets and two summaries alongside the rows, so it is not a {@code Page} and building one
 * only to take it apart would be ceremony. Two of the three sort strategies order by values computed
 * in Java — an FX-converted price and a delta — that exist in no column, so a {@code Sort} would
 * describe something we deliberately discard. And the resolver's validation is the opposite of what
 * this endpoint wants: it silently coerces a malformed or negative page to 0 and clamps against a
 * default maximum of 2000, where we want an explicit 400 and our own configured ceiling.
 */
@Slf4j
@RestController
@RequestMapping("/api/tracked-products")
@RequiredArgsConstructor
public class DashboardController {

    private static final String SHOPS_PARAM = "shops";

    private final DashboardQueryService queryService;
    private final DisplayCurrencyResolver displayCurrencyResolver;
    private final DashboardProperties dashboardProperties;

    /**
     * One page of tracked products, plus the facets and summaries the screen needs around it.
     *
     * <p>Every optional parameter is declared {@code required = false}: {@code @RequestParam} defaults
     * to required, so a bare {@code GET /api/tracked-products} would otherwise 400.
     *
     * @param allParams every query parameter, unconverted — read only for {@code shops}, and only
     *     because that one cannot survive Spring's collection conversion; see {@link #foldedShops}
     */
    @GetMapping
    public ResponseEntity<DashboardResponse> query(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String displayCurrency,
            @RequestParam MultiValueMap<String, String> allParams) {

        DashboardQueryRequest request = new DashboardQueryRequest(
                normalizeSearch(search),
                foldedShops(allParams),
                DashboardSortKey.fromParam(sort),
                validatePage(page),
                clampSize(size),
                displayCurrencyResolver.resolve(displayCurrency));

        return ResponseEntity.ok(queryService.query(request));
    }

    /** An empty or whitespace-only search is no search at all, not a match-everything substring. */
    private static String normalizeSearch(String search) {
        if (search == null) {
            return null;
        }
        String trimmed = search.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * The selected shops, read as <b>raw repeated parameter values</b> and folded to shop identities.
     *
     * <p>Deliberately not {@code @RequestParam List<String>}: Spring converts a <em>single</em>
     * parameter value into a collection by splitting it on commas. The client sends one parameter per
     * selected chip, so a shop legitimately named {@code "ACME, Inc."} would arrive as one
     * comma-bearing value and be shredded into two filters that match nothing — the chip would return
     * zero results for its own shop. Repeated values ({@code ?shops=A&shops=B}) are unaffected; it is
     * the single-value path that breaks. {@code String[]} splits identically, so the fix is to take
     * the parameter map unconverted — a nameless {@code @RequestParam MultiValueMap} is resolved by a
     * different resolver that copies it verbatim.
     *
     * <p>Blank values (a bookmarked {@code ?shops=}) are dropped rather than rejected — an empty chip
     * selection is a coherent request for everything, not a client error. Duplicates collapse after
     * folding, so {@code ?shops=Amazon&shops=amazon} is one filter.
     */
    private static List<String> foldedShops(MultiValueMap<String, String> allParams) {
        List<String> raw = allParams.get(SHOPS_PARAM);
        if (raw == null) {
            return List.of();
        }
        Set<String> folded = new LinkedHashSet<>();
        for (String shop : raw) {
            String key = ShopIdentity.of(shop);
            if (key != null) {
                folded.add(key);
            }
        }
        return List.copyOf(folded);
    }

    /** 1-based: page 0 is not "the first page", it is a client still on the old convention. */
    private static int validatePage(int page) {
        if (page < 1) {
            throw new ValidationException("page must be >= 1 (pagination is 1-based)");
        }
        return page;
    }

    /**
     * Rejects nonsense, clamps excess.
     *
     * <p>Asymmetric on purpose, mirroring {@code PriceTrendService}'s window handling: {@code size=0}
     * cannot mean anything, while {@code size=5000} is a comprehensible ask we simply will not serve
     * in full — answering with the largest page we do serve is more useful than a 400.
     */
    private int clampSize(int size) {
        if (size < 1) {
            throw new ValidationException("size must be >= 1");
        }
        int max = dashboardProperties.maxPageSize();
        if (size > max) {
            log.info("Dashboard page size clamped from {} to {}", size, max);
            return max;
        }
        return size;
    }
}
