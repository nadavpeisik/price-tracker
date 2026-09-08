package com.np.pricehunt.backend.repository.projection;

/**
 * One product the caller tracks, with the fields its detail view renders (#246). Its sibling {@link
 * TrackedProductRef} is the same product resolved the same way for a dashboard row; the two differ
 * only in shape, and the description is why — the dashboard runs over every product the caller tracks
 * and renders none of it.
 */
public record TrackedProductDetailRef(Long productId, String name, String description) {}
