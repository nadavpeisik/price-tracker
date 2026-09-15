package com.np.pricehunt.backend.dto;

/**
 * An invitation's state as the admin sees it, derived from its timestamps at read time. An outcome
 * (redeemed, revoked) outranks expiry: a consumed invitation stays consumed after its window closes.
 */
public enum InvitationStatus {
    PENDING,
    REDEEMED,
    REVOKED,
    EXPIRED
}
