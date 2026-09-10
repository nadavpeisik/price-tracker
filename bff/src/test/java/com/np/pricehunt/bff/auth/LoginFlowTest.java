package com.np.pricehunt.bff.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.np.pricehunt.bff.config.SecurityConfig;
import com.np.pricehunt.bff.controller.MeController;
import com.np.pricehunt.bff.session.SessionAttributes;
import com.np.pricehunt.bff.testsupport.BffIntegrationTest;
import com.np.pricehunt.bff.testsupport.FakeAuth0;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

/** The Authorization Code + PKCE round trip against the fake tenant, and what it leaves behind. */
class LoginFlowTest extends BffIntegrationTest {

    @Test
    void login_setsHostCookie_storesTokens_servesProfile() throws Exception {
        Browser browser = new Browser();
        MvcResult start = browser.perform(get(SecurityConfig.LOGIN_PATH)).andReturn();
        assertThat(start.getResponse().getRedirectedUrl()).isEqualTo("/bff/oauth2/authorization/auth0");

        MvcResult toAuth0 =
                browser.perform(get(start.getResponse().getRedirectedUrl())).andReturn();
        Map<String, String> authorize =
                queryParams(URI.create(toAuth0.getResponse().getRedirectedUrl()));
        assertThat(authorize)
                .containsEntry("client_id", FakeAuth0.CLIENT_ID)
                .containsEntry("audience", FakeAuth0.AUDIENCE)
                .containsEntry("code_challenge_method", "S256")
                .containsEntry("response_type", "code")
                .containsKey("code_challenge")
                .containsKey("nonce");
        assertThat(authorize.get("scope")).contains("openid", "offline_access");
        assertThat(authorize.get("redirect_uri")).endsWith("/bff/login/oauth2/code/auth0");

        MvcResult back = browser.perform(browser.consent(toAuth0.getResponse().getRedirectedUrl())
                        .request())
                .andReturn();
        assertThat(back.getResponse().getStatus()).isEqualTo(302);
        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/");

        String setCookie = back.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(header -> header.startsWith(SESSION_COOKIE + "="))
                .findFirst()
                .orElseThrow();
        assertThat(setCookie)
                .contains("Secure")
                .contains("HttpOnly")
                .contains("SameSite=Lax")
                .contains("Path=/")
                .doesNotContain("Domain=")
                .doesNotContain("Max-Age=");

        // The fake exchanged the code only after the PKCE verifier matched the challenge it recorded.
        assertThat(AUTH0.tokenCalls()).hasSize(1);
        assertThat(AUTH0.tokenCalls().get(0).grantType()).isEqualTo("authorization_code");

        browser.perform(get(MeController.PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Test User"))
                .andExpect(jsonPath("$.email").value("test@example.com"))
                .andExpect(jsonPath("$.emailVerified").value(true))
                .andExpect(jsonPath("$.picture").value("https://example.com/avatar.png"))
                .andExpect(jsonPath("$.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.sub").doesNotExist());

        assertThat(sessionRows()).isEqualTo(1);
        byte[] tokens = attributeBytes(SessionAttributes.TOKENS);
        String tokenBytes = new String(tokens, StandardCharsets.ISO_8859_1);
        assertThat(tokenBytes).contains(AUTH0.currentRefreshToken()).doesNotContain(FakeAuth0.CLIENT_SECRET);
        // Only our own record is in the stream, not Spring Security's client graph.
        assertThat(tokenBytes).doesNotContain("ClientRegistration");
        assertThat(browser.has(SecurityConfig.CSRF_COOKIE)).isTrue();
    }

    @Test
    void rememberMe_persistentCookie_longBounds() throws Exception {
        Browser browser = login(true);
        MvcResult me = browser.perform(get(MeController.PATH))
                .andExpect(status().isOk())
                .andReturn();
        // The session cookie is written at creation and id change only, never re-issued per request.
        assertThat(me.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .noneMatch(header -> header.startsWith(SESSION_COOKIE + "="));

        Integer maxInactive =
                jdbc.queryForObject("SELECT max_inactive_interval FROM bff_sessions.spring_session", Integer.class);
        assertThat(maxInactive).isEqualTo((int) Duration.ofDays(30).toSeconds());
        Instant absolute = (Instant) deserialize(attributeBytes(SessionAttributes.ABSOLUTE_EXPIRES_AT));
        assertThat(absolute).isEqualTo(clock.instant().plus(Duration.ofDays(90)));
    }

    @Test
    void rememberMe_cookieCarriesMaxAge_plainLoginDoesNot() throws Exception {
        assertThat(loginSetCookie(true)).contains("Max-Age=");
        assertThat(loginSetCookie(false)).doesNotContain("Max-Age=");
    }

    @Test
    void plainLogin_dayBounds() throws Exception {
        login(false);
        Integer maxInactive =
                jdbc.queryForObject("SELECT max_inactive_interval FROM bff_sessions.spring_session", Integer.class);
        assertThat(maxInactive).isEqualTo((int) Duration.ofHours(24).toSeconds());
        Instant absolute = (Instant) deserialize(attributeBytes(SessionAttributes.ABSOLUTE_EXPIRES_AT));
        assertThat(absolute).isEqualTo(clock.instant().plus(Duration.ofHours(24)));
    }

    @Test
    void rolesClaimAbsent_isEmptyList() throws Exception {
        AUTH0.customizeIdToken(claims -> claims.claim(FakeAuth0.ROLES_CLAIM, null));
        login().perform(get(MeController.PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles").isArray())
                .andExpect(jsonPath("$.roles").isEmpty());
    }

    // --- ID-token rejection: the decoder is hand-built, so these are what a forger would try ---

    @Test
    void idToken_wrongSignatureKey_isRejected() throws Exception {
        AUTH0.signWithUnpublishedKey();
        assertLoginFails(claims -> {});
    }

    @Test
    void idToken_wrongIssuer_isRejected() throws Exception {
        assertLoginFails(claims -> claims.issuer("http://127.0.0.1:1/"));
    }

    @Test
    void idToken_wrongAudience_isRejected() throws Exception {
        assertLoginFails(claims -> claims.audience("some-other-client"));
    }

    @Test
    void idToken_expired_isRejected() throws Exception {
        assertLoginFails(claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(600))));
    }

    // --- callback failures: the pre-login row survives, and a live session is untouched ---

    @Test
    void badState_redirectsWithLoginError_preLoginRowSurvives() throws Exception {
        Browser browser = new Browser();
        browser.perform(get(SecurityConfig.LOGIN_PATH)).andExpect(status().is3xxRedirection());
        browser.perform(get("/bff/oauth2/authorization/auth0")).andExpect(status().is3xxRedirection());
        assertThat(sessionRows()).isEqualTo(1);

        MvcResult result = browser.perform(get("/bff/login/oauth2/code/auth0")
                        .param("code", "whatever")
                        .param("state", "not-the-state"))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl())
                .startsWith("/?" + LoginFailureRedirect.ERROR_PARAMETER + "=");
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
        assertThat(sessionRows()).isEqualTo(1);
        assertThat(AUTH0.tokenCalls()).isEmpty();
    }

    @Test
    void tokenEndpointInvalidGrant_redirectsWithThatCode_preLoginRowSurvives() throws Exception {
        Browser browser = new Browser();
        browser.perform(get(SecurityConfig.LOGIN_PATH)).andExpect(status().is3xxRedirection());
        MvcResult toAuth0 =
                browser.perform(get("/bff/oauth2/authorization/auth0")).andReturn();
        Callback callback = browser.consent(toAuth0.getResponse().getRedirectedUrl());

        // A code the fake never minted.
        MvcResult result = browser.perform(callback.requestWithCode("forged")).andReturn();
        assertThat(result.getResponse().getRedirectedUrl())
                .isEqualTo("/?" + LoginFailureRedirect.ERROR_PARAMETER + "=invalid_grant");
        assertThat(sessionRows()).isEqualTo(1);
    }

    @Test
    void badState_onLiveSession_leavesItLive() throws Exception {
        Browser browser = login();
        browser.perform(get("/bff/login/oauth2/code/auth0").param("code", "x").param("state", "y"))
                .andExpect(status().is3xxRedirection());
        browser.perform(get(MeController.PATH)).andExpect(status().isOk());
        assertThat(sessionRows()).isEqualTo(1);
    }

    private void assertLoginFails(Consumer<com.nimbusds.jwt.JWTClaimsSet.Builder> customizer) throws Exception {
        AUTH0.customizeIdToken(customizer);
        Browser browser = new Browser();
        browser.perform(get(SecurityConfig.LOGIN_PATH)).andExpect(status().is3xxRedirection());
        MvcResult toAuth0 =
                browser.perform(get("/bff/oauth2/authorization/auth0")).andReturn();
        MvcResult back = browser.perform(browser.consent(toAuth0.getResponse().getRedirectedUrl())
                        .request())
                .andReturn();
        assertThat(back.getResponse().getStatus()).isEqualTo(302);
        assertThat(back.getResponse().getRedirectedUrl()).startsWith("/?" + LoginFailureRedirect.ERROR_PARAMETER + "=");
        browser.perform(get(MeController.PATH)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForList("SELECT attribute_name FROM bff_sessions.spring_session_attributes", String.class))
                .doesNotContain(SessionAttributes.ABSOLUTE_EXPIRES_AT);
    }

    private String loginSetCookie(boolean remember) throws Exception {
        Browser browser = new Browser();
        browser.perform(get(SecurityConfig.LOGIN_PATH).param("remember", String.valueOf(remember)))
                .andExpect(status().is3xxRedirection());
        MvcResult toAuth0 =
                browser.perform(get("/bff/oauth2/authorization/auth0")).andReturn();
        MvcResult back = browser.perform(browser.consent(toAuth0.getResponse().getRedirectedUrl())
                        .request())
                .andReturn();
        List<String> cookies = back.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        return cookies.stream()
                .filter(header -> header.startsWith(SESSION_COOKIE + "="))
                .findFirst()
                .orElseThrow();
    }

    static Object deserialize(byte[] bytes) throws Exception {
        try (var in = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes))) {
            return in.readObject();
        }
    }
}
