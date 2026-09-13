package com.np.pricehunt.bff.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.np.pricehunt.bff.config.SecurityConfig;
import com.np.pricehunt.bff.controller.MeController;
import com.np.pricehunt.bff.testsupport.BffIntegrationTest;
import com.np.pricehunt.bff.testsupport.FakeAuth0;
import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** {@code POST /bff/logout}: the row goes, the cookie expires, the SPA gets Auth0's logout URL. */
class LogoutTest extends BffIntegrationTest {

    @Test
    void logout_returnsAuth0Url_withoutIdToken_endsTheSession() throws Exception {
        Browser browser = login();
        browser.perform(get(MeController.PATH)).andExpect(status().isOk());

        MvcResult result = browser.perform(browser.withCsrf(post(SecurityConfig.LOGOUT_PATH)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE))
                .andExpect(jsonPath("$.logoutUrl").exists())
                .andReturn();

        String logoutUrl = JsonPath.read(result.getResponse().getContentAsString(), "$.logoutUrl");
        assertThat(logoutUrl).startsWith(AUTH0.issuer() + "oidc/logout?");
        Map<String, String> params = queryParams(URI.create(logoutUrl));
        assertThat(params)
                .containsEntry("client_id", FakeAuth0.CLIENT_ID)
                .containsEntry("logout_hint", FakeAuth0.SID)
                .containsEntry("post_logout_redirect_uri", "http://localhost/")
                .doesNotContainKey("id_token_hint");
        assertThat(logoutUrl).contains("post_logout_redirect_uri=http%3A%2F%2Flocalhost%2F");

        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(header -> header.startsWith(SESSION_COOKIE + "=") && header.contains("Max-Age=0"));
        assertThat(sessionRows()).isZero();
        browser.perform(get(MeController.PATH)).andExpect(status().isUnauthorized());
        assertThat(browser.has(SESSION_COOKIE)).isFalse();
    }

    @Test
    void anonymousLogout_withCsrfPair_is401() throws Exception {
        Browser browser = new Browser();
        // Any response carries a CSRF cookie; a 401 on /bff/me is the cheapest way to obtain one.
        browser.perform(get(MeController.PATH)).andExpect(status().isUnauthorized());
        browser.perform(browser.withCsrf(post(SecurityConfig.LOGOUT_PATH)))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/problem+json"));
        assertThat(sessionRows()).isZero();
    }
}
