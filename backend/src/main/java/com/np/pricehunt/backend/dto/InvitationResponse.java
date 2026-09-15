package com.np.pricehunt.backend.dto;

import java.time.Instant;

/** One invitation on the admin list (issue #249). Event timestamps are null until that event happens. */
public record InvitationResponse(
        long id,
        String email,
        InvitationStatus status,
        Instant createdAt,
        Instant expiresAt,
        Instant redeemedAt,
        Instant revokedAt) {}
