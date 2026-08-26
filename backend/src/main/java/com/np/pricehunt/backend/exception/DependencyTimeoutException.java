package com.np.pricehunt.backend.exception;

/** Something this request depends on (DNS, a downstream service) did not answer in time — a 504. */
public class DependencyTimeoutException extends ApplicationException {
    public DependencyTimeoutException(String message, Throwable cause) {
        super(message, cause);
    }
}
