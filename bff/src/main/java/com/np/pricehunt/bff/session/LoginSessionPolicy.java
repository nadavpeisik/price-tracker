package com.np.pricehunt.bff.session;

import com.np.pricehunt.bff.config.SessionPolicyProperties;
import com.np.pricehunt.bff.config.SessionStoreConfig;
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
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

/**
 * Turns the pre-login session into a logged-in one the moment Auth0 sends the user back: consumes
 * the remember-me choice {@code /bff/login} recorded, sets the inactivity bound Spring Session
 * enforces and the absolute bound {@link SessionExpiryFilter} enforces, asks Spring Session for a
 * persistent cookie when remembered, and sends the browser to {@code /}.
 *
 * <p>Runs after Spring Security saved the authentication into the session and changed its id, so
 * the attribute write here (flushed immediately) is also what makes the new id durable. The cookie
 * is written by Spring Session at response commit, i.e. on the redirect below, after the request
 * attribute is set.
 */
@Component
public class LoginSessionPolicy implements AuthenticationSuccessHandler {

    private static final Logger log = LoggerFactory.getLogger(LoginSessionPolicy.class);

    private final SessionPolicyProperties policy;
    private final Clock clock;
    private final RedirectStrategy redirect = new DefaultRedirectStrategy();

    public LoginSessionPolicy(SessionPolicyProperties policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        // Login only ever completes inside the pre-login session that held the authorization request.
        HttpSession session = request.getSession(false);
        if (session == null) {
            throw new IllegalStateException("Login completed without a session; the callback cannot run outside one");
        }
        boolean rememberMe = Boolean.TRUE.equals(session.getAttribute(SessionAttributes.REMEMBER));
        session.removeAttribute(SessionAttributes.REMEMBER);

        Duration inactivity = policy.inactivityTtlFor(rememberMe);
        Instant absoluteExpiresAt = clock.instant().plus(policy.absoluteTtlFor(rememberMe));
        session.setMaxInactiveInterval((int) inactivity.toSeconds());
        session.setAttribute(SessionAttributes.ABSOLUTE_EXPIRES_AT, absoluteExpiresAt);
        if (rememberMe) {
            request.setAttribute(SessionStoreConfig.REMEMBER_ME_COOKIE_REQUEST_ATTRIBUTE, Boolean.TRUE);
        }
        log.info(
                "Login succeeded for {} (rememberMe={}, inactivity={}, absoluteExpiresAt={})",
                authentication.getName(),
                rememberMe,
                inactivity,
                absoluteExpiresAt);
        redirect.sendRedirect(request, response, "/");
    }
}
