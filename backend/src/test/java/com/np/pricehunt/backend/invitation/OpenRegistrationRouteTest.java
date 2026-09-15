package com.np.pricehunt.backend.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.np.pricehunt.backend.auth.FakeIdentityProvider;
import com.np.pricehunt.backend.client.ScraperClient;
import com.np.pricehunt.backend.repository.AppUserRepository;
import com.np.pricehunt.backend.service.fx.FxRateProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The gate with {@code pricehunt.registration.invite-only=false} (#249): a verified identity gets in
 * without an invitation, an unverified one still does not, and an invitation that does match is still
 * consumed — so invitations keep working as a mechanism once signup is open. A different property
 * value is a different Spring context, hence a class of its own.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(
        properties = {
            "spring.docker.compose.enabled=false",
            "price.scheduler.enabled=false",
            "spring.ai.openai.api-key=test-key",
            "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://test-issuer.invalid/",
            "pricehunt.currency.fx.refresh-cron=-",
            "scrape.audit.purge-cron=-",
            "pricehunt.registration.invite-only=false",
        })
class OpenRegistrationRouteTest {

    private static final FakeIdentityProvider IDP = FakeIdentityProvider.start();
    private static final String GUEST = "auth0|guest";
    private static final String GUEST_EMAIL = "guest@example.com";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));

    @DynamicPropertySource
    static void wiring(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IDP::jwkSetUri);
    }

    @AfterAll
    static void stopIdentityProvider() {
        IDP.stop();
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private AppUserRepository appUsers;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ScraperClient scraperClient;

    @MockitoBean
    private FxRateProvider rateProvider;

    private MockMvc mvc;

    @BeforeEach
    void reset() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        jdbc.update("DELETE FROM invitation");
        appUsers.deleteAll();
    }

    private static String guest(boolean verified) {
        return "Bearer " + IDP.userToken(GUEST, GUEST_EMAIL, verified);
    }

    @Test
    void aVerifiedIdentity_withNoInvitation_isProvisioned() throws Exception {
        mvc.perform(post("/api/me").header(HttpHeaders.AUTHORIZATION, guest(true)))
                .andExpect(status().isCreated());

        assertThat(appUsers.findByIssuerAndSub(FakeIdentityProvider.ISSUER, GUEST))
                .get()
                .extracting(user -> user.getEmail())
                .isEqualTo(GUEST_EMAIL);
    }

    @Test
    void anUnverifiedEmail_isStill403() throws Exception {
        mvc.perform(post("/api/me").header(HttpHeaders.AUTHORIZATION, guest(false)))
                .andExpect(status().isForbidden());

        assertThat(appUsers.count()).isZero();
    }

    @Test
    void aMatchingInvitation_isStillConsumed() throws Exception {
        jdbc.update(
                "INSERT INTO invitation (email, created_at, expires_at) VALUES (?, now(), now() + interval '7 days')",
                GUEST_EMAIL);

        mvc.perform(post("/api/me").header(HttpHeaders.AUTHORIZATION, guest(true)))
                .andExpect(status().isCreated());

        Long redeemedBy = jdbc.queryForObject("SELECT redeemed_by FROM invitation", Long.class);
        assertThat(redeemedBy)
                .isEqualTo(appUsers.findByIssuerAndSub(FakeIdentityProvider.ISSUER, GUEST)
                        .orElseThrow()
                        .getId());
    }
}
