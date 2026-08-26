package com.np.pricehunt.backend.exception;

/**
 * Root of the application's failure vocabulary (issue #231). Services throw a subtype that names the
 * <em>kind</em> of failure; {@code GlobalExceptionHandler} is the only place that turns a kind into an
 * HTTP status, so nothing below the controller layer imports Spring Web.
 */
public abstract class ApplicationException extends RuntimeException {

    protected ApplicationException(String message) {
        super(message);
    }

    protected ApplicationException(String message, Throwable cause) {
        super(message, cause);
    }
}
