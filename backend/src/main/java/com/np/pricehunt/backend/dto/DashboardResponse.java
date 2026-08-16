package com.np.pricehunt.backend.dto;

import java.util.List;

/**
 * The tracked-items dashboard's entire payload: one request, one response (issue #146).
 *
 * <p>Search, shop filtering, sorting and pagination all happen server-side, so the client renders
 * what it is given and never recomputes a best price, a delta or a rollup. That is the point of the
 * envelope — the two summaries and the facet list describe data <em>outside</em> the current page, so
 * a page of rows alone could not support the screen.
 *
 * @param items the requested page, already filtered and sorted
 * @param page 1-based page metadata, echoing the request
 * @param facets the global shop list backing the filter chips — never derived from the page, or
 *     filtering to one shop would erase every other chip
 * @param globalSummary tiles over the whole tracked set, independent of the active query
 * @param summaryForCurrentQuery the same figures scoped to the search/filter, before pagination
 */
public record DashboardResponse(
        List<DashboardProductResponse> items,
        DashboardPageMeta page,
        DashboardFacets facets,
        DashboardSummary globalSummary,
        DashboardSummary summaryForCurrentQuery) {}
