package com.np.pricehunt.backend.dto;

/**
 * Page metadata for the dashboard query (issue #146).
 *
 * <p><b>{@code number} is 1-based, and echoes the validated request page verbatim.</b> That is the
 * whole contract: whatever comes back here is a value you can send straight back as {@code ?page=}.
 * Spring Data's 0-based {@code Page.getNumber()} is an internal convention, not a REST one — mixing
 * the two (0-based in, 1-based out) once meant the response's own page number silently addressed the
 * <em>next</em> page when fed back.
 *
 * <p>An overflow page is not an error: it returns empty {@code items} with truthful totals so the
 * client can clamp and re-request rather than being handed a 404 for a stale bookmark.
 *
 * @param totalElements products matching the query before pagination — a long, because it counts rows
 *     rather than pages
 * @param totalPages 0 when nothing matches, so "page 1 of 0" reads as "no results"
 */
public record DashboardPageMeta(int number, int size, long totalElements, int totalPages) {}
