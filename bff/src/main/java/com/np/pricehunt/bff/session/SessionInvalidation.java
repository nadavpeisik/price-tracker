package com.np.pricehunt.bff.session;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Ends the request's session the same way from every terminal path (policy filter, refresh
 * failure, missing tokens): the row is deleted, the cookie is expired on this response by Spring
 * Session at commit, and the security context is cleared so the rest of the chain sees an
 * anonymous request. {@code getSession(false)} throughout: a path that ends sessions must never
 * mint one.
 */
public final class SessionInvalidation {

    private SessionInvalidation() {}

    public static void invalidate(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        SecurityContextHolder.clearContext();
    }
}
