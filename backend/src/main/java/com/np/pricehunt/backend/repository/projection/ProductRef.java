package com.np.pricehunt.backend.repository.projection;

/** A product the caller tracks, resolved through membership — what the detail endpoints render (#246). */
public record ProductRef(Long id, String name, String description) {}
