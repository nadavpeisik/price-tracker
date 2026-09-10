package com.np.pricehunt.bff.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.np.pricehunt.bff.config.CorrelationIdFilter;
import com.np.pricehunt.bff.config.SecurityConfig;
import com.np.pricehunt.bff.controller.MeController;
import com.np.pricehunt.bff.testsupport.BffIntegrationTest;
import com.np.pricehunt.bff.testsupport.FakeBackend;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

/** What reaches the backend, what comes back, and the two translations the proxy makes on purpose. */
class ProxyTest extends BffIntegrationTest {

    private static final String PROBLEM_JSON = "application/problem+json";

    @Test
    void forwardsGatewayBearer_dropsBrowserCredentials() throws Exception {
        Browser browser = login();
        browser.perform(get("/bff/api/tracked-products")
                        .header(CorrelationIdFilter.HEADER, "corr-123")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer forged-by-browser")
                        .header(CSRF_HEADER, "should-not-be-forwarded")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(header().string(CorrelationIdFilter.HEADER, "corr-123"))
                .andExpect(content().json("{\"ok\":true}"));

        FakeBackend.Received received = BACKEND.last();
        assertThat(received.method()).isEqualTo("GET");
        assertThat(received.pathAndQuery()).isEqualTo("/api/tracked-products");
        assertThat(received.header("Authorization")).isEqualTo("Bearer " + AUTH0.currentAccessToken());
        assertThat(received.header("X-correlation-id")).isEqualTo("corr-123");
        assertThat(received.header("Accept")).isEqualTo(MediaType.APPLICATION_JSON_VALUE);
        assertThat(received.header("Cookie")).isNull();
        assertThat(received.header(CSRF_HEADER)).isNull();
        assertThat(received.body()).isEmpty();
    }

    @Test
    void malformedCorrelationId_isReplaced() throws Exception {
        Browser browser = login();
        MvcResult result = browser.perform(get("/bff/api/x").header(CorrelationIdFilter.HEADER, "bad id\r\ninjected"))
                .andExpect(status().isOk())
                .andReturn();
        String issued = result.getResponse().getHeader(CorrelationIdFilter.HEADER);
        assertThat(issued).matches("[A-Za-z0-9-]{1,64}");
        assertThat(BACKEND.last().header("X-correlation-id")).isEqualTo(issued);
    }

    @Test
    void post_forwardsBodyAndContentType_byteForByte() throws Exception {
        Browser browser = login();
        String body = "{\"url\":\"https://shop.example/p/1\",\"note\":\"café\"}";
        browser.perform(browser.withCsrf(post("/bff/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isOk());
        FakeBackend.Received received = BACKEND.last();
        assertThat(received.method()).isEqualTo("POST");
        assertThat(received.header("Content-type")).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(new String(received.body(), StandardCharsets.UTF_8)).isEqualTo(body);
    }

    @Test
    void bodilessPost_sendsNoBody_andNoContentType() throws Exception {
        Browser browser = login();
        browser.perform(browser.withCsrf(post("/bff/api/tracked-items/7/refresh")))
                .andExpect(status().isOk());
        FakeBackend.Received received = BACKEND.last();
        assertThat(received.body()).isEmpty();
        assertThat(received.header("Content-type")).isNull();
    }

    @Test
    void patchAndDelete_areForwarded() throws Exception {
        Browser browser = login();
        browser.perform(browser.withCsrf(patch("/bff/api/products/3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"x\"}")))
                .andExpect(status().isOk());
        assertThat(BACKEND.last().method()).isEqualTo("PATCH");
        browser.perform(browser.withCsrf(delete("/bff/api/tracked-products/3"))).andExpect(status().isOk());
        assertThat(BACKEND.last().method()).isEqualTo("DELETE");
    }

    @Test
    void query_repeatedAndEncodedParameters_reachTheBackendUnchanged() throws Exception {
        Browser browser = login();
        // MockMvc percent-encodes query values itself (a raw URL is a template to it), as a browser would.
        browser.perform(get("/bff/api/tracked-products")
                        .queryParam("shops", "b")
                        .queryParam("shops", "a")
                        .queryParam("q", "café x")
                        .queryParam("page", "1"))
                .andExpect(status().isOk());
        assertThat(BACKEND.last().pathAndQuery())
                .isEqualTo("/api/tracked-products?shops=b&shops=a&q=caf%C3%A9%20x&page=1");
        browser.perform(get("/bff/api/products/3")).andExpect(status().isOk());
        assertThat(BACKEND.last().pathAndQuery()).isEqualTo("/api/products/3");
    }

    @Test
    void malformedPercentEscape_is400_andNeverReachesTheBackend() throws Exception {
        Browser browser = login();
        // Set the raw query directly: MockMvc would percent-encode the stray '%' away.
        browser.perform(get("/bff/api/products").with(request -> {
                    request.setQueryString("q=100%");
                    return request;
                }))
                .andExpect(status().isBadRequest())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, PROBLEM_JSON));
        assertThat(BACKEND.received()).isEmpty();
        assertThat(sessionRows()).isEqualTo(1);
    }

    @Test
    void backendStatusAndContentType_passThrough() throws Exception {
        Browser browser = login();
        for (int status : new int[] {200, 201, 403, 404, 409}) {
            BACKEND.respondWith(
                    new FakeBackend.Scripted(status, PROBLEM_JSON, "{\"status\":" + status + "}", Map.of()));
            browser.perform(get("/bff/api/x"))
                    .andExpect(status().is(status))
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, PROBLEM_JSON))
                    .andExpect(jsonPath("$.status").value(status));
        }
    }

    @Test
    void backendSetCookieAndInternalHeaders_areNotCopied() throws Exception {
        Browser browser = login();
        BACKEND.respondWith(new FakeBackend.Scripted(
                200, "application/json", "{}", Map.of("Set-Cookie", "leak=1", "X-Backend-Internal", "yes")));
        MvcResult result =
                browser.perform(get("/bff/api/x")).andExpect(status().isOk()).andReturn();
        assertThat(result.getResponse().getHeader("X-Backend-Internal")).isNull();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(h -> h.startsWith("leak="));
    }

    @Test
    void backend401_becomes502_sessionIntact() throws Exception {
        Browser browser = login();
        BACKEND.respondWith(new FakeBackend.Scripted(
                401, PROBLEM_JSON, "{}", Map.of("WWW-Authenticate", "Bearer error=\"invalid_token\"")));
        browser.perform(get("/bff/api/x"))
                .andExpect(status().isBadGateway())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, PROBLEM_JSON));
        assertThat(sessionRows()).isEqualTo(1);
        browser.perform(get(MeController.PATH)).andExpect(status().isOk());
    }

    @Test
    void stalledBackend_is502ProblemDetail_sessionIntact() throws Exception {
        Browser browser = login();
        // The test profile's read timeout is 1.5 s; a stall past it is the same transport failure as a
        // refused connection or a broken stream, and gets the same one 502.
        BACKEND.stall();
        try {
            browser.perform(get("/bff/api/x"))
                    .andExpect(status().isBadGateway())
                    .andExpect(header().string(HttpHeaders.CONTENT_TYPE, PROBLEM_JSON));
        } finally {
            BACKEND.unstall();
        }
        assertThat(sessionRows()).isEqualTo(1);
    }

    @Test
    void anonymous_is401_andCreatesNoSessionRow() throws Exception {
        Browser browser = new Browser();
        browser.perform(get("/bff/api/tracked-products").accept(MediaType.TEXT_HTML))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, PROBLEM_JSON));
        browser.perform(get("/bff/api/tracked-products").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
        browser.perform(get(MeController.PATH).accept(MediaType.TEXT_HTML)).andExpect(status().isUnauthorized());
        assertThat(sessionRows()).isZero();
        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void loginOnLiveSession_redirectsHome_andTouchesNothing() throws Exception {
        Browser browser = login();
        String refreshBefore = AUTH0.currentRefreshToken();
        MvcResult result = browser.perform(get(SecurityConfig.LOGIN_PATH)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(sessionRows()).isEqualTo(1);
        assertThat(AUTH0.authorizeRequests()).hasSize(1);
        assertThat(new String(attributeBytes("bff.tokens"), StandardCharsets.ISO_8859_1))
                .contains(refreshBefore);
    }

    @Test
    void authorizationRouteOnLiveSession_redirectsHome_andStartsNoNewAttempt() throws Exception {
        Browser browser = login();
        MvcResult result =
                browser.perform(get("/bff/oauth2/authorization/auth0")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(302);
        assertThat(result.getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(AUTH0.authorizeRequests()).hasSize(1);
        assertThat(sessionRows()).isEqualTo(1);
        browser.perform(get(MeController.PATH)).andExpect(status().isOk());
    }

    @Test
    void sessionDeletedUnderneathTheRequest_is401() throws Exception {
        Browser browser = login();
        // The filter loaded the row for this request; the proxy's own fresh read must not find it.
        jdbc.update("DELETE FROM bff_sessions.spring_session");
        browser.perform(get("/bff/api/x")).andExpect(status().isUnauthorized());
        assertThat(BACKEND.received()).isEmpty();
    }

    @Test
    void scopeParameter_doesNotReachTheTokenEndpoint() throws Exception {
        Browser browser = login();
        clock.advance(java.time.Duration.ofMinutes(10));
        browser.perform(get("/bff/api/x").queryParam("scope", "admin everything"))
                .andExpect(status().isOk());
        assertThat(AUTH0.refreshCalls()).hasSize(1);
        assertThat(AUTH0.refreshCalls().get(0).form()).doesNotContainKey("scope");
        assertThat(BACKEND.last().pathAndQuery()).isEqualTo("/api/x?scope=admin%20everything");
    }
}
