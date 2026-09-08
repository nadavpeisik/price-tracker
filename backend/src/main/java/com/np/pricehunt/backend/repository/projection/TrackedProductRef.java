package com.np.pricehunt.backend.repository.projection;

/** A product on one user's dashboard: the id and its label, nothing computed (issue #246). */
public record TrackedProductRef(Long productId, String name) {}
