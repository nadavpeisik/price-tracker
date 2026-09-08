package com.np.pricehunt.backend.repository.projection;

/**
 * One product on the caller's dashboard: the id and its label, nothing computed (issue #246). Its
 * sibling {@link TrackedProductDetailRef} carries the description a detail view needs.
 */
public record TrackedProductRef(Long productId, String name) {}
