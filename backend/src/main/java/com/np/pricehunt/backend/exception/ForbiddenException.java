package com.np.pricehunt.backend.exception;

/**
 * The caller is authenticated but not admitted or not permitted (403). Distinct from a 401, which means
 * a missing or invalid token and never reaches application code, and from the 404 that #246 will use for
 * "exists but is not yours" so other users' rows are never confirmed.
 */
public class ForbiddenException extends ApplicationException {

    public ForbiddenException(String message) {
        super(message);
    }
}
