package com.np.pricehunt.bff.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Spring Security 6+ defers CSRF token creation until something reads it, so the cookie would never
 * be written on a plain {@code GET /bff/me}. Reading the token here, right after {@code CsrfFilter},
 * puts {@code __Host-XSRF-TOKEN} on every response so the SPA always has one to send back. Registered
 * inside the chain by {@code SecurityConfig}, not as a {@code @Component}.
 */
public class CsrfCookieFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        CsrfToken token = (CsrfToken) request.getAttribute(CsrfToken.class.getName());
        if (token != null) {
            token.getToken();
        }
        chain.doFilter(request, response);
    }
}
