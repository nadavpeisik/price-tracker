package com.np.pricehunt.backend.dto;

/**
 * Body of {@code PATCH /api/tracked-products/{productId}/listings/{itemId}} (issue #250). Boxed so a
 * missing member is told apart from {@code false} and rejected as a 400 by the service, like every
 * other request body here.
 */
public record SetListingHiddenRequest(Boolean hidden) {}
