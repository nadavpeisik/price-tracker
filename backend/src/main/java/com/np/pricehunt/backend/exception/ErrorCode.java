package com.np.pricehunt.backend.exception;

/**
 * Machine-readable identity of an error a client must act on differently from its status-mates
 * (issue #173). Emitted as the {@code errorCode} member of the ProblemDetail; the human {@code detail}
 * stays free to change. Only conflicts with distinct remedies are listed — a code is added when a
 * client needs it, never speculatively.
 */
public enum ErrorCode {
    /** Another product already owns this name (case-insensitive); pick another name. */
    PRODUCT_NAME_ALREADY_EXISTS,
    /** This URL is a listing of a different product; add it there, not here. */
    URL_TRACKED_BY_ANOTHER_PRODUCT,
    /** This product has its maximum number of listings; remove one first. */
    PRODUCT_LISTING_LIMIT_REACHED
}
