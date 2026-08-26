package com.np.pricehunt.backend.exception;

/** The request is malformed or breaks a business rule on its own terms — a 400 at the boundary. */
public class ValidationException extends ApplicationException {
    public ValidationException(String message) {
        super(message);
    }

    public ValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
