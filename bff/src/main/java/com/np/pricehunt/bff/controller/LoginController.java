package com.np.pricehunt.bff.controller;

import com.np.pricehunt.bff.config.Auth0ClientRegistrationConfig;
import com.np.pricehunt.bff.config.OAuth2ClientConfig;
import com.np.pricehunt.bff.config.SecurityConfig;
import com.np.pricehunt.bff.session.SessionAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The SPA's entry into the login flow. Records the remember-me choice in a (pre-login) session and
 * hands over to Spring's authorization-request redirect.
 *
 * <p>On a live session it answers {@code 302 /} and touches nothing: login only ever runs inside a
 * pre-login session, so no row is ever re-authenticated in place (which would let an in-flight
 * refresh for identity A land in a row that now belongs to B), and a Lax cookie riding a cross-site
 * navigation cannot make this GET delete or alter anything. Switching accounts is logout first.
 */
@RestController
public class LoginController {

    static final String AUTHORIZATION_PATH =
            OAuth2ClientConfig.AUTHORIZATION_BASE_URI + "/" + Auth0ClientRegistrationConfig.REGISTRATION_ID;

    /**
     * Only a live session reaches this mapping: for anyone else Spring's redirect filter has already
     * answered {@code /bff/oauth2/authorization/auth0} ahead of MVC. Same answer as a live
     * {@code /bff/login}.
     */
    @GetMapping(AUTHORIZATION_PATH)
    public void authorizationOnLiveSession(HttpServletResponse response) throws IOException {
        response.sendRedirect("/");
    }

    @GetMapping(SecurityConfig.LOGIN_PATH)
    public void login(
            @RequestParam(defaultValue = "false") boolean remember,
            Authentication authentication,
            HttpServletRequest request,
            HttpServletResponse response)
            throws IOException {
        if (authentication instanceof OAuth2AuthenticationToken) {
            response.sendRedirect("/");
            return;
        }
        // Always overwritten: remember=false after an earlier remember=true must win.
        request.getSession().setAttribute(SessionAttributes.REMEMBER, remember);
        response.sendRedirect(AUTHORIZATION_PATH);
    }
}
