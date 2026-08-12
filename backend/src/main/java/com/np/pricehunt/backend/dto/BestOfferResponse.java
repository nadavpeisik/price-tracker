package com.np.pricehunt.backend.dto;

import java.time.Instant;

/**
 * Which listing supplied a series point's price, and when that price was last observed.
 *
 * <p>{@code observedAt} is older than the point's own day whenever the price was carried forward
 * through a scrape gap, which is what lets a client say "best known price — last checked 3 days ago"
 * instead of overclaiming that the shop was selling at that price that day.
 */
public record BestOfferResponse(Long trackedItemId, String shopName, Instant observedAt) {}
