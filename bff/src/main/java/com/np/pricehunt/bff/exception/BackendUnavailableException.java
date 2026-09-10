package com.np.pricehunt.bff.exception;

/**
 * The backend could not be reached or did not answer in time. One kind for every transport failure
 * (connect refused, read timeout, broken stream): the SPA has nothing different to do for each.
 */
public final class BackendUnavailableException extends BffException {

    public BackendUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
