package com.np.pricehunt.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.service.fx.FxRateProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The actuator rules on the production topology: management on its own port, in Boot's child context
 * (issue #245). The only test here that boots Tomcat, because a MOCK environment creates no child
 * context and Boot refuses a management address on a shared port. What it pins is that the parent's
 * security chain governs the child: health anonymous, everything else {@code ADMIN} by role alone,
 * since an ops identity needs no {@code app_user} row.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "spring.docker.compose.enabled=false",
            "price.scheduler.enabled=false",
            "pricehunt.currency.fx.refresh-cron=-",
            "scrape.audit.purge-cron=-",
            "management.server.port=0",
        })
class ManagementPortPostureTest {

    private static final FakeIdentityProvider IDP = FakeIdentityProvider.start();

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IDP::jwkSetUri);
    }

    @AfterAll
    static void stopIdentityProvider() {
        IDP.stop();
    }

    @Autowired
    private Environment environment;

    @MockitoBean
    private FxRateProvider fxRateProvider;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void managementPort_healthIsAnonymous_everythingElseNeedsAdminRole() throws Exception {
        // The one anonymous endpoint, so what it discloses is part of the security posture:
        // management.endpoint.health.show-details defaults to NEVER, and this is the assertion that
        // notices if anyone sets it to always. With details on, an unauthenticated caller would read
        // component health -- database reachability, disk space, the Ollama and scraper probes.
        HttpResponse<String> health = get("/actuator/health", null);
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"").doesNotContain("components");

        HttpResponse<String> anonymous = get("/actuator/metrics", null);
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(anonymous.headers().firstValue(HttpHeaders.WWW_AUTHENTICATE)).hasValue("Bearer");

        assertThat(get("/actuator/metrics", IDP.userToken()).statusCode()).isEqualTo(403);
        assertThat(get("/actuator/metrics", IDP.adminToken("auth0|ops-without-an-account"))
                        .statusCode())
                .isEqualTo(200);
        // The links root is part of toAnyEndpoint() (includeLinks = true); a non-admin cannot list the
        // exposed endpoints either.
        assertThat(get("/actuator", null).statusCode()).isEqualTo(401);
        assertThat(get("/actuator", IDP.userToken()).statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> get(String path, String bearer) throws IOException, InterruptedException {
        int port = environment.getRequiredProperty("local.management.port", Integer.class);
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .GET();
        if (bearer != null) {
            request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearer);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
