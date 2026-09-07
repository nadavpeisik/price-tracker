package com.np.pricehunt.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.np.pricehunt.backend.service.fx.FxRateProvider;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The identity provider is up but not answering: the JWKS fetch must give up at the configured read
 * timeout, and the request must fail as a 503 rather than a 401 or a 500. A decoder builder has no
 * getter, so this elapsed-time window is the only observable proof that {@code SecurityConfig}'s JWKS
 * client is the one in force; the configured value is deliberately not Spring Security's own 500 ms
 * default, or the window could not tell them apart.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.docker.compose.enabled=false",
            "price.scheduler.enabled=false",
            "pricehunt.currency.fx.refresh-cron=-",
            "scrape.audit.purge-cron=-",
            "pricehunt.auth.jwks.read-timeout=2s",
        })
class JwksReadTimeoutTest {

    private static final FakeIdentityProvider IDP = FakeIdentityProvider.start();

    @DynamicPropertySource
    static void slowIdentityProvider(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IDP::slowJwkSetUri);
    }

    @AfterAll
    static void stopIdentityProvider() {
        IDP.stop();
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private FxRateProvider fxRateProvider;

    @Test
    void jwksFetchTimeout_is503_withinTheConfiguredBound() throws Exception {
        long startedNanos = System.nanoTime();
        mvc.perform(get("/api/products/1").header(HttpHeaders.AUTHORIZATION, "Bearer " + IDP.userToken()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.detail").value("Authentication service unavailable"));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - startedNanos);
        // The fixture sleeps 5 s. Below the lower bound the framework's 500 ms default answered, not
        // our 2 s; above the upper bound nothing bounded the fetch at all.
        assertThat(elapsed).isBetween(Duration.ofMillis(1_900), Duration.ofSeconds(4));
    }
}
