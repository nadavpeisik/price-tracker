package com.np.pricehunt.bff.auth;

import com.np.pricehunt.bff.config.Auth0ClientRegistrationConfig;
import com.np.pricehunt.bff.controller.SecurityRejectionRouter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.util.UrlUtils;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
import tools.jackson.databind.json.JsonMapper;

/**
 * Answers {@code POST /bff/logout} with {@code 200 {"logoutUrl": ...}} once Spring has invalidated
 * the session: a {@code fetch} cannot usefully follow a cross-origin 302 into Auth0, so the SPA
 * navigates there itself. Not Spring's {@code OidcClientInitiatedLogoutSuccessHandler}, which puts
 * the raw ID token on the URL as {@code id_token_hint} and would hand token material to SPA
 * JavaScript. Auth0 accepts {@code client_id} in its place; without any hint it interposes a "log
 * out?" page, so the ID token's {@code sid} (an Auth0 session identifier, not a credential) goes along
 * as {@code logout_hint} when present.
 *
 * <p>{@code LogoutFilter} runs ahead of authorization, so an anonymous caller reaches this handler:
 * it is routed to the 401 advice, nothing else happens.
 */
@Component
public class LogoutUrlResponder implements LogoutSuccessHandler {

    static final String LOGOUT_URL_FIELD = "logoutUrl";
    static final String SID_CLAIM = "sid";

    private final ClientRegistrationRepository registrations;
    private final SecurityRejectionRouter router;
    private final JsonMapper json;

    public LogoutUrlResponder(
            ClientRegistrationRepository registrations, SecurityRejectionRouter router, JsonMapper json) {
        this.registrations = registrations;
        this.router = router;
        this.json = json;
    }

    @Override
    public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        if (!(authentication instanceof OAuth2AuthenticationToken token)) {
            router.commence(request, response, new InsufficientAuthenticationException("Not logged in"));
            return;
        }
        String logoutUrl = logoutUrl(request, token);
        response.setStatus(HttpStatus.OK.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream()
                .write(json.writeValueAsString(Map.of(LOGOUT_URL_FIELD, logoutUrl))
                        .getBytes(StandardCharsets.UTF_8));
    }

    private String logoutUrl(HttpServletRequest request, OAuth2AuthenticationToken token) {
        ClientRegistration registration =
                registrations.findByRegistrationId(Auth0ClientRegistrationConfig.REGISTRATION_ID);
        String endSessionEndpoint = (String) registration
                .getProviderDetails()
                .getConfigurationMetadata()
                .get(Auth0ClientRegistrationConfig.END_SESSION_ENDPOINT);

        // Bound as URI variables so every value is strictly percent-encoded.
        Map<String, String> params = new LinkedHashMap<>();
        params.put("client_id", registration.getClientId());
        if (token.getPrincipal() instanceof OidcUser user && user.getClaimAsString(SID_CLAIM) != null) {
            params.put("logout_hint", user.getClaimAsString(SID_CLAIM));
        }
        params.put("post_logout_redirect_uri", postLogoutRedirectUri(request));

        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(endSessionEndpoint);
        params.keySet().forEach(name -> builder.queryParam(name, "{" + name + "}"));
        return builder.encode().build().expand(params).toUriString();
    }

    /** Scheme, host and port of this request (forwarded headers honoured), the way Spring's handler does. */
    private static String postLogoutRedirectUri(HttpServletRequest request) {
        return UriComponentsBuilder.fromUriString(UrlUtils.buildFullRequestUrl(request))
                .replacePath("/")
                .replaceQuery(null)
                .fragment(null)
                .build()
                .toUriString();
    }
}
