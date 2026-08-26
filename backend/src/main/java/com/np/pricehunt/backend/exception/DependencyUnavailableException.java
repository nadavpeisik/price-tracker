package com.np.pricehunt.backend.exception;

/** Something this request depends on (DNS, a downstream service) is currently unavailable — a 503. */
public class DependencyUnavailableException extends ApplicationException {
    public DependencyUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
