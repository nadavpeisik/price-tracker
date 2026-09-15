package com.np.pricehunt.backend.auth;

/**
 * What the token says about the caller before any account exists (issue #249): the identity key and
 * the email the identity provider vouches for. {@code email} is null when the token carries none;
 * {@code emailVerified} is what the provider asserted, never inferred.
 */
public record ClaimedIdentity(String issuer, String sub, String email, boolean emailVerified) {}
