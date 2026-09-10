package com.np.pricehunt.bff.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.np.pricehunt.bff.config.SecurityConfig;
import com.np.pricehunt.bff.testsupport.BffIntegrationTest;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Every controller mapping, enumerated: anonymous GET is a JSON 401, anonymous unsafe methods are a
 * 403 without the CSRF pair and a 401 with one. The allow-list is exactly {@code /bff/login}. The three
 * Spring-provided routes are handled by filters ahead of authorization, so enumeration cannot see
 * them and they are pinned by explicit cases; the same rule the backend's posture test states.
 */
class SecurityPostureTest extends BffIntegrationTest {

    /** Login, and the authorization route Spring's redirect filter answers for anonymous callers. */
    private static final Set<String> ANONYMOUS_ALLOWED =
            Set.of(SecurityConfig.LOGIN_PATH, "/bff/oauth2/authorization/auth0");

    private static final String PROBLEM_JSON = "application/problem+json";

    @Autowired
    private RequestMappingHandlerMapping mappings;

    @Test
    void everyMapping_anonymousGetIs401_exceptLogin() throws Exception {
        Set<String> paths = new TreeSet<>();
        for (RequestMappingInfo info : mappings.getHandlerMethods().keySet()) {
            for (String pattern : info.getPatternValues()) {
                paths.add(pattern.replace("/**", "/probe").replace("{id}", "1"));
            }
        }
        assertThat(paths).contains("/bff/login", "/bff/me", "/bff/api/probe");

        for (String path : paths) {
            MvcResult result = new Browser().perform(get(path)).andReturn();
            if (ANONYMOUS_ALLOWED.contains(path)) {
                assertThat(result.getResponse().getStatus()).as(path).isEqualTo(302);
            } else {
                assertThat(result.getResponse().getStatus()).as(path).isEqualTo(401);
                assertThat(result.getResponse().getContentType()).as(path).startsWith(PROBLEM_JSON);
            }
        }
        assertThat(sessionRows())
                .as("anonymous GETs mint no session rows (except the two login routes)")
                .isEqualTo(2);
    }

    @Test
    void anonymousMutations_are403WithoutCsrf_401WithIt() throws Exception {
        for (HttpMethod method : new HttpMethod[] {HttpMethod.POST, HttpMethod.PATCH, HttpMethod.DELETE}) {
            Browser browser = new Browser();
            browser.perform(request(method, "/bff/api/probe")).andExpect(status().isForbidden());
            browser.perform(get("/bff/me")).andExpect(status().isUnauthorized());
            browser.perform(browser.withCsrf(request(method, "/bff/api/probe")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, PROBLEM_JSON));
        }
        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void filterProvidedRoutes_arePinned() throws Exception {
        Browser browser = new Browser();
        MvcResult authorize =
                browser.perform(get("/bff/oauth2/authorization/auth0")).andReturn();
        assertThat(authorize.getResponse().getStatus()).isEqualTo(302);
        assertThat(authorize.getResponse().getRedirectedUrl()).startsWith(AUTH0.issuer() + "authorize?");

        MvcResult callback =
                new Browser().perform(get("/bff/login/oauth2/code/auth0")).andReturn();
        assertThat(callback.getResponse().getStatus()).isEqualTo(302);
        assertThat(callback.getResponse().getRedirectedUrl())
                .startsWith("/?" + LoginFailureRedirect.ERROR_PARAMETER + "=");

        Browser logout = new Browser();
        logout.perform(post(SecurityConfig.LOGOUT_PATH)).andExpect(status().isForbidden());
        logout.perform(get("/bff/me")).andExpect(status().isUnauthorized());
        logout.perform(logout.withCsrf(post(SecurityConfig.LOGOUT_PATH))).andExpect(status().isUnauthorized());
        // GET is not a logout.
        logout.perform(get(SecurityConfig.LOGOUT_PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void unmappedPath_onLiveSession_is404ProblemDetail() throws Exception {
        login().perform(get("/"))
                .andExpect(status().isNotFound())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, PROBLEM_JSON));
    }
}
