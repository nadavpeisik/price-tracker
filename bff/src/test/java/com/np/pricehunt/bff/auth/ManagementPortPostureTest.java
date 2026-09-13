package com.np.pricehunt.bff.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.bff.config.SecurityConfig;
import com.np.pricehunt.bff.testsupport.BffIntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

/**
 * The one suite that boots Tomcat: the management port lives in Boot's child context, which a MOCK
 * environment never creates. Pins that health is anonymous there and nothing else is exposed, that
 * the main port serves no actuator at all, and (real container, real cookie processor) the CSRF
 * cookie's full attribute line, which MockMvc's response cannot render.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "management.server.port=0")
class ManagementPortPostureTest extends BffIntegrationTest {

    @LocalServerPort
    private int port;

    @LocalManagementPort
    private int managementPort;

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void managementPort_healthAnonymous_nothingElseExposed() throws Exception {
        HttpResponse<String> health = get(managementPort, "/actuator/health");
        assertThat(health.statusCode()).isEqualTo(200);
        assertThat(health.body()).contains("\"status\":\"UP\"").doesNotContain("components");

        // An anonymous 401 alone would not prove an endpoint is unexposed (the fallback rule answers 401
        // either way), so the exposure set is asserted directly and the 401s pin the chain on top.
        assertThat(environment.getProperty("management.endpoints.web.exposure.include"))
                .isEqualTo("health");
        assertThat(get(managementPort, "/actuator").statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/actuator/env").statusCode()).isEqualTo(401);
        assertThat(get(managementPort, "/actuator/sessions").statusCode()).isEqualTo(401);
    }

    @Test
    void mainPort_hasNoActuator_andWritesTheFullCsrfCookieLine() throws Exception {
        assertThat(get(port, "/actuator/health").statusCode()).isEqualTo(401);

        HttpResponse<String> me = get(port, "/bff/me");
        assertThat(me.statusCode()).isEqualTo(401);
        assertThat(me.headers().firstValue(HttpHeaders.CONTENT_TYPE))
                .hasValueSatisfying(type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(me.headers().allValues(HttpHeaders.SET_COOKIE))
                .anyMatch(cookie -> cookie.startsWith(SecurityConfig.CSRF_COOKIE + "=")
                        && cookie.contains("Path=/")
                        && cookie.contains("Secure")
                        && cookie.contains("SameSite=Lax")
                        && !cookie.contains("HttpOnly"));
        assertThat(me.headers().allValues(HttpHeaders.SET_COOKIE)).noneMatch(c -> c.startsWith(SESSION_COOKIE + "="));
    }

    private HttpResponse<String> get(int onPort, String path) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + onPort + path))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
