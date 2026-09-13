package com.np.pricehunt.bff.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

/**
 * A failed callback is a top-level browser navigation, so the answer is a redirect the SPA can
 * render ({@code /?loginError=<code>}), not a JSON 401. The code is the OAuth2 error code filtered to
 * {@code [a-z_]{1,64}}, else {@code unknown}; nothing Auth0 said reaches the URL verbatim.
 *
 * <p>It touches no session. The callback is an anonymous GET on which a Lax cookie rides, so a
 * handler that invalidated would let any site log a user out by sending them to the callback with a
 * bad {@code state}; and a pre-login session may carry a second, still-valid attempt (two tabs; the
 * latest attempt wins, the earlier callback lands here). Abandoned pre-login rows expire by
 * inactivity.
 */
@Component
public class LoginFailureRedirect implements AuthenticationFailureHandler {

    public static final String ERROR_PARAMETER = "loginError";
    static final String UNKNOWN = "unknown";

    private static final Logger log = LoggerFactory.getLogger(LoginFailureRedirect.class);
    private static final Pattern SAFE_CODE = Pattern.compile("[a-z_]{1,64}");

    @Override
    public void onAuthenticationFailure(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
            throws IOException {
        String code = errorCode(exception);
        log.warn("Login failed ({}): {}", code, exception.getMessage());
        response.sendRedirect("/?" + ERROR_PARAMETER + "=" + code);
    }

    static String errorCode(AuthenticationException exception) {
        if (exception instanceof OAuth2AuthenticationException oauth2) {
            String code = oauth2.getError().getErrorCode();
            if (code != null && SAFE_CODE.matcher(code).matches()) {
                return code;
            }
        }
        return UNKNOWN;
    }
}
