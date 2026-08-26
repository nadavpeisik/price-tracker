package com.np.pricehunt.backend.exception;

/** The client asked again too soon; the same request succeeds after the cooldown — a 429. */
public class RefreshCooldownException extends ApplicationException {
    public RefreshCooldownException(String message) {
        super(message);
    }
}
