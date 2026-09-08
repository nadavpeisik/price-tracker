package com.np.pricehunt.backend.repository.projection;

import java.time.Instant;

/**
 * One listing under a product the caller tracks (issue #246): what a user-driven refresh needs (id,
 * url, lastChecked) plus the shop name for the price-history header. The user reaches it through the
 * product, never directly — a listing is a shared catalog row — so holding one is proof the caller
 * tracks the product above it, not that the listing is theirs.
 */
public record TrackedListingRef(Long id, String url, String shopName, Instant lastChecked) {}
