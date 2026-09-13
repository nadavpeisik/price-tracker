package com.np.pricehunt.bff.exception;

/**
 * Root of the BFF's own failures, sealed so {@code controller/GlobalExceptionHandler} can switch over the
 * kinds exhaustively: a new subtype that nobody mapped to a status is a compile error, not a 500
 * discovered in production. Same contract as the backend's {@code ApplicationException} (#231): the
 * subtype names the kind of failure, the advice alone decides the HTTP status.
 */
public abstract sealed class BffException extends RuntimeException
        permits InvalidProxyTargetException,
                SessionRevokedException,
                IdentityProviderUnavailableException,
                BackendUnavailableException,
                BackendRejectedGatewayTokenException {

    protected BffException(String message) {
        super(message);
    }

    protected BffException(String message, Throwable cause) {
        super(message, cause);
    }
}
