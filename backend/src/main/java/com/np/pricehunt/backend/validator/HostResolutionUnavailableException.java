package com.np.pricehunt.backend.validator;

/**
 * The host resolver could not attempt (or complete) a lookup because its bounded worker pool was
 * saturated, or the calling thread was interrupted while waiting. Distinct from a lookup that ran and
 * failed ({@link java.net.UnknownHostException}) or ran but exceeded the timeout budget
 * ({@link java.util.concurrent.TimeoutException}). Mapped to HTTP 503 by {@link UrlValidator}.
 */
public class HostResolutionUnavailableException extends RuntimeException {
    public HostResolutionUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
