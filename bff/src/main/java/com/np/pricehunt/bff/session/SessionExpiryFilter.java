package com.np.pricehunt.bff.session;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Applies one state table to every request that arrives with a session, right after Spring
 * Security loaded the context from it:
 *
 * <pre>
 *   authenticated | absoluteExpiresAt        | verdict
 *   yes           | present, in the future   | live
 *   no            | absent                   | pre-login (holds the authorization request): left alone
 *   anything else                            | invalidate: row deleted, cookie expired, request goes on as anonymous
 * </pre>
 *
 * Absolute expiry is ours to enforce because Spring Session only knows inactivity. "Anything else"
 * covers an authenticated row whose bound is missing or unreadable, and a row whose security context
 * could not be deserialized (the tolerant converter turns that into an absent authentication).
 *
 * <p>On a live request the row's inactivity interval is also clamped to what remains of the absolute
 * bound, so the database expiry can never outlive it: a remember-me session touched on day 89 would
 * otherwise keep its serialized tokens in the table until day 119. The clamp compares against the
 * session's own interval (24 h or 30 d, set at login), never a default, and only ever shortens.
 *
 * <p>Not a {@code @Component}: registered inside the security chain by {@code SecurityConfig}, so
 * Boot does not also install it as a container-wide filter.
 */
public class SessionExpiryFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(SessionExpiryFilter.class);

    private final Clock clock;

    public SessionExpiryFilter(Clock clock) {
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        if (session == null) {
            chain.doFilter(request, response);
            return;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean authenticated = authentication instanceof OAuth2AuthenticationToken;
        Instant absoluteExpiresAt = absoluteExpiresAt(session);
        Instant now = clock.instant();

        if (authenticated && absoluteExpiresAt != null && absoluteExpiresAt.isAfter(now)) {
            clampInactivityToAbsoluteBound(session, absoluteExpiresAt, now);
        } else if (authenticated || absoluteExpiresAt != null) {
            log.info(
                    "Invalidating session (authenticated={}, absoluteExpiresAt={}, now={})",
                    authenticated,
                    absoluteExpiresAt,
                    now);
            SessionInvalidation.invalidate(request);
        }
        chain.doFilter(request, response);
    }

    private static Instant absoluteExpiresAt(HttpSession session) {
        return session.getAttribute(SessionAttributes.ABSOLUTE_EXPIRES_AT) instanceof Instant instant ? instant : null;
    }

    private static void clampInactivityToAbsoluteBound(HttpSession session, Instant absoluteExpiresAt, Instant now) {
        long remainingSeconds = Duration.between(now, absoluteExpiresAt).getSeconds();
        if (remainingSeconds < session.getMaxInactiveInterval()) {
            // Zero means "never expire" to Spring Session, hence the floor of one second.
            session.setMaxInactiveInterval((int) Math.max(1, remainingSeconds));
        }
    }
}
