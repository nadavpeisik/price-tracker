package com.np.pricehunt.bff.exception;

/**
 * The session can no longer obtain a usable access token: Auth0 rejected the refresh grant
 * ({@code invalid_grant} / {@code invalid_token}), the stored tokens are gone or unreadable, or
 * there is no refresh token to use. Terminal: the thrower invalidates the session, the advice
 * answers 401, and the SPA sends the user back through {@code /bff/login}.
 */
public final class SessionRevokedException extends BffException {

    public SessionRevokedException(String message) {
        super(message);
    }

    public SessionRevokedException(String message, Throwable cause) {
        super(message, cause);
    }
}
