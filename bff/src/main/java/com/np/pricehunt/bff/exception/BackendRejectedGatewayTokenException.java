package com.np.pricehunt.bff.exception;

/**
 * The backend answered 401 to a token the BFF just minted or refreshed. That is never "log in
 * again": it is a configuration fault between the two deployables (audience, issuer, clock), so it
 * is reported as a 502 rather than passed through, or a SPA that treats 401 as "go log in" would
 * loop between {@code /bff/login} and the API for as long as the fault lasts.
 */
public final class BackendRejectedGatewayTokenException extends BffException {

    public BackendRejectedGatewayTokenException(String message) {
        super(message);
    }
}
