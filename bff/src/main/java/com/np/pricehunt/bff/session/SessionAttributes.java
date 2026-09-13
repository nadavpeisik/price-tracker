package com.np.pricehunt.bff.session;

/** Names of the attributes the BFF keeps in a session row, in one place so the policy and the token store agree. */
public final class SessionAttributes {

    /** {@code Boolean}: the remember-me choice {@code /bff/login} recorded, consumed once on login success. */
    public static final String REMEMBER = "bff.remember";

    /** {@code Instant}: the absolute bound of a logged-in session; absent on a pre-login session. */
    public static final String ABSOLUTE_EXPIRES_AT = "bff.absoluteExpiresAt";

    /** {@code token.StoredTokens}: the Auth0 access + refresh tokens of a logged-in session. */
    public static final String TOKENS = "bff.tokens";

    private SessionAttributes() {}
}
