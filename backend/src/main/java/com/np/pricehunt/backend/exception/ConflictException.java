package com.np.pricehunt.backend.exception;

import java.util.Objects;

/**
 * The request collides with current state the client can change and resubmit — a 409. Every conflict
 * carries an {@link ErrorCode}: the remedies differ per conflict, and the status alone cannot say
 * which one applies (issue #173). Conflicts are the only kind that carries a code today; move the
 * field up to {@link ApplicationException} when a second kind needs one, not before.
 */
public class ConflictException extends ApplicationException {

    private final transient ErrorCode errorCode;

    public ConflictException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = Objects.requireNonNull(errorCode, "every conflict carries an ErrorCode");
    }

    /** The machine-readable identity a client switches on; the message is prose. */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
