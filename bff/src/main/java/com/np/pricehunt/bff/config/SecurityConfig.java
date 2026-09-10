package com.np.pricehunt.bff.config;

import com.np.pricehunt.bff.auth.CsrfCookieFilter;
import com.np.pricehunt.bff.auth.LoginFailureRedirect;
import com.np.pricehunt.bff.auth.LogoutUrlResponder;
import com.np.pricehunt.bff.controller.SecurityRejectionRouter;
import com.np.pricehunt.bff.session.LoginSessionPolicy;
import com.np.pricehunt.bff.session.SessionExpiryFilter;
import java.time.Clock;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * The cookie-session posture (issue #247). Explicit {@code /bff} prefixes everywhere and no
 * {@code context-path}: a context path would make {@code Path=/bff} the cookie default, which the
 * {@code __Host-} prefix forbids, and turn every "redirect to /" into "/bff/".
 *
 * <pre>
 *   /actuator/health (management port)      anonymous
 *   GET  /bff/login                          anonymous  -> 302 /bff/oauth2/authorization/auth0
 *   GET  /bff/oauth2/authorization/auth0     anonymous  (Spring: PKCE + audience request)
 *   GET  /bff/login/oauth2/code/auth0        anonymous  (Spring: callback; success -> 302 /)
 *   GET  /bff/me                             authenticated
 *   POST /bff/logout                         authenticated + CSRF -> 200 {logoutUrl}
 *   ANY  /bff/api/**                         authenticated + CSRF on mutations -> proxied
 *   anything else                            401
 * </pre>
 *
 * The three Spring-provided routes are handled by filters ahead of authorization, so the mapping
 * enumeration test cannot see them; the posture test pins them by explicit cases. Every
 * {@code oauth2Login} collaborator is wired here by hand rather than left to bean pick-up, so the
 * framework defaults (an un-timed token client, storing the raw {@code OAuth2AuthorizedClient}) can
 * never silently win.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    public static final String LOGIN_PATH = "/bff/login";
    public static final String LOGOUT_PATH = "/bff/logout";
    public static final String CALLBACK_BASE_URI = "/bff/login/oauth2/code/*";
    public static final String CSRF_COOKIE = "__Host-XSRF-TOKEN";

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ClientRegistrationRepository registrations,
            OAuth2AuthorizedClientRepository authorizedClients,
            OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> authorizationCodeTokenClient,
            OAuth2AuthorizationRequestResolver authorizationRequestResolver,
            OidcUserService oidcUserService,
            LoginSessionPolicy loginSessionPolicy,
            LoginFailureRedirect loginFailureRedirect,
            LogoutUrlResponder logoutUrlResponder,
            SecurityRejectionRouter router,
            Clock clock)
            throws Exception {
        return http.csrf(csrf -> csrf.csrfTokenRepository(csrfTokenRepository())
                        // Plain, not XOR: the token is never rendered into HTML, so BREACH does not apply.
                        .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
                .addFilterAfter(new CsrfCookieFilter(), CsrfFilter.class)
                .addFilterAfter(new SessionExpiryFilter(clock), SecurityContextHolderFilter.class)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                // Security 7's default cache saves an anonymous browser GET into a session before the
                // entry point answers, which would let unauthenticated navigations mint session rows.
                // The authorization request lives in its own repository; the success redirect is always /.
                .requestCache(cache -> cache.disable())
                .exceptionHandling(
                        handling -> handling.authenticationEntryPoint(router).accessDeniedHandler(router))
                .oauth2Login(login -> login.clientRegistrationRepository(registrations)
                        .authorizedClientRepository(authorizedClients)
                        // Ours, so Spring never serves its generated page (and never permits it).
                        .loginPage(LOGIN_PATH)
                        .authorizationEndpoint(endpoint -> endpoint.baseUri(OAuth2ClientConfig.AUTHORIZATION_BASE_URI)
                                .authorizationRequestResolver(authorizationRequestResolver))
                        .redirectionEndpoint(endpoint -> endpoint.baseUri(CALLBACK_BASE_URI))
                        .tokenEndpoint(endpoint -> endpoint.accessTokenResponseClient(authorizationCodeTokenClient))
                        .userInfoEndpoint(endpoint -> endpoint.oidcUserService(oidcUserService))
                        .successHandler(loginSessionPolicy)
                        .failureHandler(loginFailureRedirect))
                // POST only: CsrfFilter runs ahead of LogoutFilter, so the header is still required.
                .logout(logout -> logout.logoutUrl(LOGOUT_PATH).logoutSuccessHandler(logoutUrlResponder))
                .authorizeHttpRequests(authorize -> authorize
                        // The management port's one exposed endpoint; the parent chain governs the child.
                        .requestMatchers(EndpointRequest.to("health"))
                        .permitAll()
                        .requestMatchers(LOGIN_PATH)
                        .permitAll()
                        .anyRequest()
                        .authenticated())
                .build();
    }

    /**
     * {@code __Host-}: Secure, Path=/, no Domain. Readable by the SPA (not HttpOnly), which sends it
     * back as {@code X-XSRF-TOKEN}. Lax, matching the session cookie.
     */
    private static CookieCsrfTokenRepository csrfTokenRepository() {
        CookieCsrfTokenRepository repository = new CookieCsrfTokenRepository();
        repository.setCookieName(CSRF_COOKIE);
        repository.setCookiePath("/");
        repository.setCookieCustomizer(
                cookie -> cookie.secure(true).httpOnly(false).path("/").sameSite("Lax"));
        return repository;
    }
}
