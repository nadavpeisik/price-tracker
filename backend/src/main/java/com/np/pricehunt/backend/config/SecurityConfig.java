package com.np.pricehunt.backend.config;

import static org.springframework.security.authorization.AuthenticatedAuthorizationManager.authenticated;
import static org.springframework.security.authorization.AuthorityAuthorizationManager.hasRole;
import static org.springframework.security.authorization.AuthorizationManagers.allOf;

import com.np.pricehunt.backend.auth.AdmissionAuthorizationManager;
import com.np.pricehunt.backend.controller.SecurityRejectionRouter;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.util.List;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.servlet.JwkSetUriJwtDecoderBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AuthenticationEntryPointFailureHandler;
import org.springframework.web.client.RestTemplate;

/**
 * The bearer-only security posture (issue #245): every request carries an Auth0 JWT or is rejected;
 * nothing cookie-shaped exists here because the browser talks to a separate BFF (#247). Token
 * validation is Boot's own — {@code issuer-uri}, {@code jwk-set-uri}, {@code audiences} and the
 * roles-claim converter are properties, not classes, because a hand-built decoder would replace Boot's
 * validators rather than add to them.
 *
 * <p>{@code authenticated()} is composed in front of {@link AdmissionAuthorizationManager} so the rules
 * state the invariant themselves rather than relying on the manager's internals.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final String ADMIN_ROLE = "ADMIN";

    public SecurityConfig(OAuth2ResourceServerProperties resourceServer) {
        requireWellFormedIdp(
                resourceServer.getJwt().getIssuerUri(), resourceServer.getJwt().getAudiences());
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            SecurityRejectionRouter router,
            AdmissionAuthorizationManager admission,
            JwtAuthenticationConverter jwtAuthenticationConverter)
            throws Exception {
        return http
                // Bearer transport has no cookie to forge, so there is no CSRF surface (Sonar S4502).
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Redundant under STATELESS, which installs NullRequestCache; kept so the no-session
                // contract is stated rather than inherited.
                .requestCache(cache -> cache.disable())
                // Spring's default LogoutFilter would answer /logout (any method, with CSRF off) with a
                // 302 ahead of the authorization rules, and the mapping enumeration test cannot see a
                // filter-provided route.
                .logout(logout -> logout.disable())
                .exceptionHandling(
                        handling -> handling.authenticationEntryPoint(router).accessDeniedHandler(router))
                .oauth2ResourceServer(
                        oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                                // BearerTokenAuthenticationFilter calls this one directly for a bad token; the
                                // exceptionHandling entry point above covers everything else.
                                .authenticationEntryPoint(router)
                                .accessDeniedHandler(router)
                                .withObjectPostProcessor(routeIdpOutagesThroughTheAdvice(router)))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(EndpointRequest.to("health"))
                        .permitAll()
                        // toAnyEndpoint() includes the /actuator links root. Loggers and thread dumps
                        // make this an admin resource even on a loopback-only port.
                        .requestMatchers(EndpointRequest.toAnyEndpoint())
                        .hasRole(ADMIN_ROLE)
                        .requestMatchers("/api/dev/**")
                        .access(allOf(authenticated(), hasRole(ADMIN_ROLE), admission))
                        // Editing or hard-deleting a catalog row changes it for every user, so those
                        // verbs are admin (#246). A user's own removal is DELETE /api/tracked-products/{id}.
                        .requestMatchers(HttpMethod.PATCH, "/api/products/**")
                        .access(allOf(authenticated(), hasRole(ADMIN_ROLE), admission))
                        .requestMatchers(HttpMethod.DELETE, "/api/products/**")
                        .access(allOf(authenticated(), hasRole(ADMIN_ROLE), admission))
                        .requestMatchers("/api/**")
                        .access(allOf(authenticated(), admission))
                        .anyRequest()
                        .authenticated())
                .build();
    }

    /**
     * The bearer filter's default failure handler <em>rethrows</em> {@code AuthenticationServiceException},
     * the type a failed JWKS fetch surfaces as, so an identity-provider outage would escape the chain as
     * a container 500 instead of this API's {@code ProblemDetail}. Same handler, minus that rethrow.
     */
    private static ObjectPostProcessor<BearerTokenAuthenticationFilter> routeIdpOutagesThroughTheAdvice(
            SecurityRejectionRouter router) {
        return new ObjectPostProcessor<>() {
            @Override
            public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                var failureHandler = new AuthenticationEntryPointFailureHandler(router);
                failureHandler.setRethrowAuthenticationServiceException(false);
                filter.setAuthenticationFailureHandler(failureHandler);
                return filter;
            }
        };
    }

    /**
     * Owns the timeouts of the JWKS fetch, the decoder's only network call once {@code jwk-set-uri} is
     * explicit. Spring Security's own default is Nimbus's 500 ms connect and read, tight enough that a
     * cold fetch to Auth0 across the internet can turn a slow p99 into a 503. HTTP/1.1 because a
     * cleartext h2c upgrade confuses simple servers such as the test JWKS fixture.
     */
    @Bean
    JwkSetUriJwtDecoderBuilderCustomizer jwksClientTimeoutCustomizer(JwksClientProperties jwksClient) {
        RestTemplate timedClient = new RestTemplate(RestClientFactories.timed(
                jwksClient.connectTimeout(), jwksClient.readTimeout(), HttpClient.Version.HTTP_1_1));
        return builder -> builder.restOperations(timedClient);
    }

    /**
     * Fails the boot on a misconfigured identity provider rather than on the first bearer request. The
     * trailing slash is load-bearing twice: the issuer validator compares it byte-for-byte with the
     * token's {@code iss}, and {@code jwk-set-uri} is derived from it. An empty audience list is the
     * quieter trap, because Boot then installs no {@code aud} validator at all and a token minted for
     * any other API in the tenant is accepted. As with {@code GroqLlmConfig.requireApiKey}, this is also
     * what catches an unset {@code AUTH0_ISSUER_URI}: relaxed binding passes the literal {@code ${...}}
     * through, and it fails the scheme check.
     */
    static void requireWellFormedIdp(String issuerUri, List<String> audiences) {
        if (!isHttpsRootWithHost(issuerUri)) {
            throw new IllegalStateException("AUTH0_ISSUER_URI must be an absolute https:// URL with a host, ending"
                    + " in '/' (the Auth0 tenant issuer, e.g. https://tenant.eu.auth0.com/), but was: " + issuerUri);
        }
        if (audiences == null || audiences.isEmpty() || audiences.stream().allMatch(String::isBlank)) {
            throw new IllegalStateException("AUTH0_AUDIENCE must name the API audience (e.g. pricehunt-api):"
                    + " with no audience configured, tokens minted for any other API in the tenant would be"
                    + " accepted");
        }
    }

    private static boolean isHttpsRootWithHost(String issuerUri) {
        if (issuerUri == null || !issuerUri.endsWith("/")) {
            return false;
        }
        try {
            URI uri = new URI(issuerUri);
            return "https".equals(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
