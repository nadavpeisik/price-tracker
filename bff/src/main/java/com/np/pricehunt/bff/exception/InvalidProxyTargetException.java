package com.np.pricehunt.bff.exception;

/** The proxied path and query could not form a URI. Tomcat and the firewall reject these first. */
public final class InvalidProxyTargetException extends BffException {

    public InvalidProxyTargetException(String message, Throwable cause) {
        super(message, cause);
    }
}
