package com.np.pricehunt.backend.exception;

/** The addressed resource does not exist, or is not reachable through the path it was addressed by — a 404. */
public class NotFoundException extends ApplicationException {
    public NotFoundException(String message) {
        super(message);
    }
}
