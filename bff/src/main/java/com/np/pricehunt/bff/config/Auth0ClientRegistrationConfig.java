package com.np.pricehunt.bff.config;

import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;

/**
 * The one {@link ClientRegistration}, built by hand from the issuer the way the backend derives its
 * {@code jwk-set-uri} (#245): Auth0's endpoints sit at fixed paths under the tenant, so nothing is
 * gained by OIDC discovery and something is lost. Boot's {@code issuer-uri} path would call
 * {@code ClientRegistrations.fromIssuerLocation} at startup on an un-timed {@code RestTemplate}, and a
 * slow tenant would hang the boot. Every Auth0 call this module makes goes through a timed client.
 *
 * <p>{@code end_session_endpoint} is put in the provider metadata so the logout handler reads it from
 * the registration, and {@code issuerUri} so the ID-token validator checks {@code iss}.
 */
@Configuration
public class Auth0ClientRegistrationConfig {

    public static final String REGISTRATION_ID = "auth0";
    public static final String END_SESSION_ENDPOINT = "end_session_endpoint";

    @Bean
    ClientRegistrationRepository clientRegistrationRepository(Auth0ClientProperties auth0) {
        return new InMemoryClientRegistrationRepository(
                registration(auth0.issuerUri(), auth0.clientId(), auth0.clientSecret()));
    }

    /** Shared with the test fixture, which points the same shape at a loopback fake. */
    public static ClientRegistration registration(String issuerUri, String clientId, String clientSecret) {
        return ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/bff/login/oauth2/code/{registrationId}")
                // offline_access is what makes Auth0 issue a refresh token.
                .scope("openid", "profile", "email", "offline_access")
                .authorizationUri(issuerUri + "authorize")
                .tokenUri(issuerUri + "oauth/token")
                .jwkSetUri(issuerUri + ".well-known/jwks.json")
                .userInfoUri(issuerUri + "userinfo")
                .issuerUri(issuerUri)
                .providerConfigurationMetadata(Map.of(END_SESSION_ENDPOINT, issuerUri + "oidc/logout"))
                .clientName("Auth0")
                .build();
    }
}
