package com.np.pricehunt.backend.dto;

import java.util.List;

/**
 * One dashboard query, already normalized and validated by the controller (issue #146).
 *
 * <p>Immutable and fully resolved: by the time the service sees it, {@code search} is trimmed or
 * null, {@code shops} is a de-duplicated list of fold keys, {@code sort} is a known strategy,
 * {@code page}/{@code size} are within bounds, and {@code displayCurrency} is a supported ISO code.
 * The service therefore never re-validates or reaches for a default — every rejection has already
 * happened at the HTTP boundary, where it can be a 400 with a useful message.
 *
 * @param search case-insensitive substring of the product name; null means "no search"
 * @param shops folded shop keys to filter by; empty means "every shop"
 * @param page <b>1-based</b>, matching what the response echoes back
 * @param displayCurrency the currency every money value in the response is expressed in — part of the
 *     query, not a separate argument, because it decides the converted prices, the sparklines and the
 *     order of the cheapest-first sort
 */
public record DashboardQueryRequest(
        String search, List<String> shops, DashboardSortKey sort, int page, int size, String displayCurrency) {

    /** Defensive copy, so the immutability the javadoc claims is enforced rather than conventional. */
    public DashboardQueryRequest {
        shops = List.copyOf(shops);
    }
}
