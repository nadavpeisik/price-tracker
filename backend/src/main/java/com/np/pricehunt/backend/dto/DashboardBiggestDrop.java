package com.np.pricehunt.backend.dto;

import java.math.BigDecimal;

/**
 * The biggest 7-day price fall in a set, enough to render and link the tile (issue #146).
 *
 * <p>The name travels with the id on purpose: the winning product is frequently not on the current
 * page, so the tile has nothing to look it up in.
 *
 * <p>{@code deltaPct} is a JSON <em>number</em> (percent, scale 2) rather than a money string — it is
 * a ratio, not an amount, and nothing downstream does currency arithmetic with it.
 */
public record DashboardBiggestDrop(Long productId, String productName, BigDecimal deltaPct) {}
