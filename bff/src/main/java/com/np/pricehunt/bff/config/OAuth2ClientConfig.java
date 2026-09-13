package com.np.pricehunt.bff.config;

import com.np.pricehunt.bff.token.SingleFlightRefreshCoordinator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.http.HttpClient;
import java.time.Clock;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.converter.FormHttpMessageConverter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProvider;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.RemoveAuthorizedClientOAuth2AuthorizationFailureHandler;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest;
import org.springframework.security.oauth2.client.endpoint.RestClientAuthorizationCodeTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.RestClientRefreshTokenTokenResponseClient;
import org.springframework.security.oauth2.client.http.OAuth2ErrorResponseErrorHandler;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.http.converter.OAuth2AccessTokenResponseHttpMessageConverter;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Every outbound Auth0 call on a timed client, and every framework default that would quietly do
 * otherwise replaced explicitly: the token endpoint clients, the ID-token JWKS fetch, the
 * authorization request (PKCE + {@code audience}), the user service (no {@code /userinfo} round trip),
 * and the manager that runs the refresh grant. HTTP/1.1 throughout, as the backend's JWKS client:
 * over cleartext the JDK's h2c upgrade attempt confuses the loopback test fixture, and Auth0 over
 * TLS is fine on 1.1.
 */
@Configuration
public class OAuth2ClientConfig {

    /** Auth0 mints a JWT for the API only when the authorization request names it. */
    static final String AUDIENCE_PARAMETER = "audience";

    public static final String AUTHORIZATION_BASE_URI = "/bff/oauth2/authorization";

    @Bean
    OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenClient(
            Auth0ClientProperties auth0) {
        var client = new RestClientAuthorizationCodeTokenResponseClient();
        client.setRestClient(tokenEndpointClient(auth0));
        return client;
    }

    @Bean
    OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenClient(Auth0ClientProperties auth0) {
        var client = new RestClientRefreshTokenTokenResponseClient();
        client.setRestClient(tokenEndpointClient(auth0));
        return client;
    }

    /**
     * The framework's own default client plus our request factory: the form and token-response
     * converters and the error handler are what parse a token response and surface
     * {@code invalid_grant} as itself, which the refresh coordinator's terminal/transient split relies
     * on. A bare timed client would do neither.
     */
    private static RestClient tokenEndpointClient(Auth0ClientProperties auth0) {
        return RestClient.builder()
                .requestFactory(timedFactory(auth0))
                .configureMessageConverters(converters -> {
                    converters.addCustomConverter(new FormHttpMessageConverter());
                    converters.addCustomConverter(new OAuth2AccessTokenResponseHttpMessageConverter());
                })
                .defaultStatusHandler(new OAuth2ErrorResponseErrorHandler())
                .build();
    }

    private static ClientHttpRequestFactory timedFactory(Auth0ClientProperties auth0) {
        return RestClientFactories.timed(auth0.connectTimeout(), auth0.readTimeout(), HttpClient.Version.HTTP_1_1);
    }

    @Bean
    JwtDecoderFactory<ClientRegistration> idTokenDecoderFactory(Auth0ClientProperties auth0) {
        return new Auth0IdTokenDecoderFactory(new RestTemplate(timedFactory(auth0)));
    }

    /**
     * PKCE is not automatic for a confidential client, and {@code audience} is Auth0-specific. Wrapped so
     * a live session gets no authorization request: Spring's redirect filter runs ahead of authorization
     * and would otherwise re-authenticate a logged-in row in place, which is the one thing the session
     * model forbids (a row never changes identity). Declining lets the request fall through to
     * {@code LoginController}, which answers {@code 302 /} as it does for {@code /bff/login}.
     */
    @Bean
    OAuth2AuthorizationRequestResolver authorizationRequestResolver(
            ClientRegistrationRepository registrations, Auth0ClientProperties auth0) {
        var resolver = new DefaultOAuth2AuthorizationRequestResolver(registrations, AUTHORIZATION_BASE_URI);
        resolver.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
                .andThen(builder -> builder.additionalParameters(
                        parameters -> parameters.put(AUDIENCE_PARAMETER, auth0.audience()))));
        return new PreLoginOnlyAuthorizationRequestResolver(resolver);
    }

    /** Resolves authorization requests only while nobody is logged in on the session. */
    static final class PreLoginOnlyAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {

        private final OAuth2AuthorizationRequestResolver delegate;

        PreLoginOnlyAuthorizationRequestResolver(OAuth2AuthorizationRequestResolver delegate) {
            this.delegate = delegate;
        }

        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
            return alreadyAuthenticated() ? null : delegate.resolve(request);
        }

        @Override
        public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
            return alreadyAuthenticated() ? null : delegate.resolve(request, clientRegistrationId);
        }

        private static boolean alreadyAuthenticated() {
            return SecurityContextHolder.getContext().getAuthentication() instanceof OAuth2AuthenticationToken;
        }
    }

    /**
     * Auth0 puts {@code name}, {@code email}, {@code email_verified} and {@code picture} in the ID token
     * for the requested scopes, so the {@code /userinfo} round trip (on the framework's un-timed default
     * client) is skipped.
     */
    @Bean
    OidcUserService oidcUserService() {
        var service = new OidcUserService();
        service.setRetrieveUserInfo(request -> false);
        return service;
    }

    /**
     * Runs the refresh grant for {@link SingleFlightRefreshCoordinator}. Refresh provider only: the
     * login's code exchange is the login filter's, and an authorization-code provider here would
     * throw "authorization required" for a row deleted mid-flight where the coordinator wants the
     * provider's "cannot refresh" null instead.
     */
    @Bean
    OAuth2AuthorizedClientManager authorizedClientManager(
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository authorizedClients,
            OAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> refreshTokenClient,
            Clock clock) {
        OAuth2AuthorizedClientProvider provider = OAuth2AuthorizedClientProviderBuilder.builder()
                .refreshToken(refresh -> refresh.accessTokenResponseClient(refreshTokenClient)
                        .clock(clock)
                        .clockSkew(SingleFlightRefreshCoordinator.REFRESH_SKEW))
                .build();
        var manager = new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
        manager.setAuthorizedClientProvider(provider);
        // The default mapper copies a "scope" request parameter into the refresh grant, which would let
        // the browser rewrite the scopes the BFF asks Auth0 for via /bff/api/...?scope=.
        manager.setContextAttributesMapper(request -> Map.of());
        // Same as the manager's default, set explicitly: drops the stored tokens on exactly the codes
        // the coordinator treats as terminal (invalid_grant, invalid_token), so both layers agree.
        manager.setAuthorizationFailureHandler(new RemoveAuthorizedClientOAuth2AuthorizationFailureHandler(
                (registrationId, principal, attributes) -> authorizedClients.removeAuthorizedClient(
                        registrationId,
                        principal,
                        (HttpServletRequest) attributes.get(HttpServletRequest.class.getName()),
                        (HttpServletResponse) attributes.get(HttpServletResponse.class.getName()))));
        return manager;
    }
}
