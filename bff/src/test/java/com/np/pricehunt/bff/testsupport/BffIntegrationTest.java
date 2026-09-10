package com.np.pricehunt.bff.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.np.pricehunt.bff.config.SecurityConfig;
import jakarta.servlet.http.Cookie;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Shared setup for the Docker-backed suites: the real init script's role and schema in a Testcontainers
 * Postgres (connected to as {@code bff_session}, never the superuser), the loopback {@link FakeAuth0}
 * and {@link FakeBackend}, a movable clock, and a {@link Browser} that carries cookies between MockMvc
 * requests the way a real browser would. Every suite truncates the session table before each test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(FakeAuth0Config.class)
public abstract class BffIntegrationTest {

    public static final FakeAuth0 AUTH0 = FakeAuth0.start();
    public static final FakeBackend BACKEND = FakeBackend.start();

    public static final String SESSION_COOKIE = "__Host-pricehunt-session";
    public static final String CSRF_HEADER = "X-XSRF-TOKEN";

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        var postgres = TestPostgres.container();
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> TestPostgres.BFF_ROLE);
        registry.add("spring.datasource.password", () -> TestPostgres.BFF_PASSWORD);
        registry.add("pricehunt.bff.backend.base-url", BACKEND::baseUrl);
    }

    @Autowired
    protected MockMvc mvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected MutableClock clock;

    @BeforeEach
    void resetFixtures() {
        jdbc.execute("TRUNCATE bff_sessions.spring_session CASCADE");
        AUTH0.reset();
        BACKEND.reset();
        clock.reset();
    }

    protected long sessionRows() {
        return jdbc.queryForObject("SELECT count(*) FROM bff_sessions.spring_session", Long.class);
    }

    protected byte[] attributeBytes(String attributeName) {
        return jdbc.queryForObject(
                "SELECT attribute_bytes FROM bff_sessions.spring_session_attributes WHERE attribute_name = ?",
                byte[].class,
                attributeName);
    }

    /** Runs the whole login flow for a fresh browser and returns it logged in. */
    protected Browser login() throws Exception {
        return login(false);
    }

    protected Browser login(boolean remember) throws Exception {
        Browser browser = new Browser();
        MvcResult start = browser.perform(get(SecurityConfig.LOGIN_PATH).param("remember", String.valueOf(remember)))
                .andReturn();
        assertThat(start.getResponse().getStatus()).isEqualTo(302);
        MvcResult toAuth0 =
                browser.perform(get(start.getResponse().getRedirectedUrl())).andReturn();
        assertThat(toAuth0.getResponse().getStatus()).isEqualTo(302);
        String authorizeUrl = toAuth0.getResponse().getRedirectedUrl();
        assertThat(authorizeUrl).startsWith(AUTH0.issuer() + "authorize?");

        // The fake auto-consents: the code it would redirect back with is the one its /authorize minted.
        Map<String, String> authorize = queryParams(URI.create(authorizeUrl));
        MvcResult back =
                browser.perform(browser.consent(authorizeUrl).request()).andReturn();
        assertThat(back.getResponse().getStatus()).as("callback response").isEqualTo(302);
        assertThat(back.getResponse().getRedirectedUrl()).isEqualTo("/");
        assertThat(authorize).containsKey("code_challenge");
        return browser;
    }

    public static Map<String, String> queryParams(URI uri) {
        Map<String, String> params = new LinkedHashMap<>();
        String query = uri.getRawQuery();
        if (query == null) {
            return params;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            params.put(
                    URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
        }
        return params;
    }

    /** What Auth0 sends the browser back with. Decoded, because MockMvc encodes a request URL itself. */
    public record Callback(String path, String code, String state) {
        public MockHttpServletRequestBuilder request() {
            return get(path).queryParam("code", code).queryParam("state", state);
        }

        public MockHttpServletRequestBuilder requestWithCode(String otherCode) {
            return get(path).queryParam("code", otherCode).queryParam("state", state);
        }
    }

    /** A cookie jar over MockMvc: sends what it holds, keeps what {@code Set-Cookie} says. */
    public class Browser {

        // Concurrent: the single-flight suite drives one browser from several threads.
        private final Map<String, Cookie> cookiesByName = new java.util.concurrent.ConcurrentHashMap<>();

        public org.springframework.test.web.servlet.ResultActions perform(MockHttpServletRequestBuilder request)
                throws Exception {
            if (!cookiesByName.isEmpty()) {
                request.cookie(cookiesByName.values().toArray(Cookie[]::new));
            }
            var actions = mvc.perform(request);
            remember(actions.andReturn());
            return actions;
        }

        /**
         * Sends a mutation with the CSRF header the cookie jar holds. Login clears the CSRF cookie and
         * the next response reissues it, so a browser straight out of login fetches {@code /bff/me}
         * first, exactly as the SPA does on load.
         */
        public MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
            if (!cookiesByName.containsKey(SecurityConfig.CSRF_COOKIE)) {
                perform(get("/bff/me"));
            }
            Cookie csrf = cookiesByName.get(SecurityConfig.CSRF_COOKIE);
            assertThat(csrf).as("CSRF cookie in the jar").isNotNull();
            return request.header(CSRF_HEADER, csrf.getValue());
        }

        public String cookie(String name) {
            Cookie cookie = cookiesByName.get(name);
            return cookie == null ? null : cookie.getValue();
        }

        public boolean has(String name) {
            return cookiesByName.containsKey(name);
        }

        /** Visits the fake's authorize URL over real HTTP and returns the callback it redirects to. */
        public Callback consent(String authorizeUrl) throws Exception {
            var http = java.net.http.HttpClient.newBuilder()
                    .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                    .build();
            var response = http.send(
                    java.net.http.HttpRequest.newBuilder(URI.create(authorizeUrl))
                            .GET()
                            .build(),
                    java.net.http.HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(302);
            String location =
                    response.headers().firstValue(HttpHeaders.LOCATION).orElseThrow();
            URI target = URI.create(location);
            Map<String, String> params = queryParams(target);
            return new Callback(target.getPath(), params.get("code"), params.get("state"));
        }

        private void remember(MvcResult result) {
            List<String> setCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
            for (String header : setCookies) {
                String[] parts = header.split(";");
                String[] nameValue = parts[0].split("=", 2);
                String name = nameValue[0].trim();
                String value = nameValue.length > 1 ? nameValue[1].trim() : "";
                boolean expired = header.contains("Max-Age=0");
                if (expired || value.isEmpty()) {
                    cookiesByName.remove(name);
                } else {
                    cookiesByName.put(name, new Cookie(name, value));
                }
            }
        }
    }
}
