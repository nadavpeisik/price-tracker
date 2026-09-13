package com.np.pricehunt.bff.exception;

/**
 * A refresh could not complete for a reason that says nothing about the session: Auth0 timed out,
 * refused the connection or answered 5xx/429, or this request gave up waiting for another request's
 * in-flight refresh. Transient: the session and its tokens stay, the advice answers 503, the next
 * call retries.
 */
public final class IdentityProviderUnavailableException extends BffException {

    public IdentityProviderUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
