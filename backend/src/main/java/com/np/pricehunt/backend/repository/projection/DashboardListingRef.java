package com.np.pricehunt.backend.repository.projection;

/**
 * The three things the dashboard's whole-set pass needs to know about a listing (issue #146).
 *
 * <p>Everything else — url, lastChecked, the lazy {@code priceHistory} collection — is dead weight
 * when the pass runs over every listing on every request, so this stays a flat projection rather than
 * a {@code TrackedItem}. Only the rows on the requested page are hydrated as entities, and only
 * because the trend engine's sparkline API takes entities.
 *
 * <p>{@code shopName} is <b>nullable</b>: the column has always been (V1), and detection can leave it
 * unset. Every consumer must go through the shared fold helper rather than touching it directly.
 */
public record DashboardListingRef(Long trackedItemId, Long productId, String shopName) {}
