package com.np.pricehunt.bff.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.np.pricehunt.bff.config.SecurityConfig;
import com.np.pricehunt.bff.controller.MeController;
import com.np.pricehunt.bff.testsupport.BffIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

/** The double-submit pair: {@code __Host-XSRF-TOKEN} cookie in, {@code X-XSRF-TOKEN} header back. */
class CsrfTest extends BffIntegrationTest {

    @Test
    void mutationWithoutHeader_is403_withHeader_isForwarded() throws Exception {
        Browser browser = login();
        browser.perform(post("/bff/api/products")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/problem+json"));
        assertThat(BACKEND.received()).isEmpty();

        browser.perform(browser.withCsrf(post("/bff/api/products")
                        .contentType("application/json")
                        .content("{}")))
                .andExpect(status().isOk());
        assertThat(BACKEND.received()).hasSize(1);
    }

    @Test
    void wrongHeader_is403() throws Exception {
        Browser browser = login();
        browser.perform(delete("/bff/api/tracked-products/1").header(CSRF_HEADER, "not-the-cookie-value"))
                .andExpect(status().isForbidden());
        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void get_needsNoHeader_andAlwaysCarriesTheCookie() throws Exception {
        Browser browser = login();
        MvcResult result = browser.perform(get(MeController.PATH))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(browser.has(SecurityConfig.CSRF_COOKIE)).isTrue();
        // Re-issued on the first response after login cleared it; readable by the SPA (no HttpOnly).
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .anyMatch(cookie -> cookie.startsWith(SecurityConfig.CSRF_COOKIE + "=")
                        && cookie.contains("Secure")
                        && cookie.contains("Path=/")
                        && !cookie.contains("HttpOnly")
                        && !cookie.contains("Domain="));
    }

    @Test
    void logoutWithoutHeader_is403_sessionSurvives() throws Exception {
        Browser browser = login();
        browser.perform(post(SecurityConfig.LOGOUT_PATH)).andExpect(status().isForbidden());
        assertThat(sessionRows()).isEqualTo(1);
        browser.perform(get(MeController.PATH)).andExpect(status().isOk());
    }
}
