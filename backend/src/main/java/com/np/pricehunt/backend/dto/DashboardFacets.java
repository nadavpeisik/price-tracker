package com.np.pricehunt.backend.dto;

import java.util.List;

/**
 * The filter values the dashboard offers, derived from the whole tracked set (issue #146).
 *
 * <p>{@code shops} is the set of distinct shop <em>identities</em>, each rendered by its most common
 * stored spelling — not a distinct list of raw strings. Casing is presentation, so "Amazon" and
 * "amazon" are one chip; see {@code DashboardQueryService} for the fold and issue #166 for fixing the
 * spelling drift at its source.
 *
 * <p>Listings with no shop name are excluded: a chip nobody could meaningfully select is noise.
 */
public record DashboardFacets(List<String> shops) {}
