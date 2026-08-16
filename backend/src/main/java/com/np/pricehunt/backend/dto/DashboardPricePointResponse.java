package com.np.pricehunt.backend.dto;

import java.time.Instant;

/**
 * One point of a dashboard row's sparkline (issue #146).
 *
 * <p>Deliberately narrower than {@link TrendPointResponse}: the sparkline is a shape, not a table, so
 * the winning listing behind each day is dropped. That detail belongs to the product-detail chart,
 * which asks for it explicitly.
 *
 * <p>{@code price} is a decimal string — see {@link DashboardProductResponse} for why money never
 * crosses this boundary as a JSON number.
 */
public record DashboardPricePointResponse(Instant t, String price) {}
