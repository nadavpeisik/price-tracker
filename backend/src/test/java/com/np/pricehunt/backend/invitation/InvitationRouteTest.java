package com.np.pricehunt.backend.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.np.pricehunt.backend.auth.FakeIdentityProvider;
import com.np.pricehunt.backend.client.ScraperClient;
import com.np.pricehunt.backend.domain.AppUser;
import com.np.pricehunt.backend.repository.AppUserRepository;
import com.np.pricehunt.backend.service.fx.FxRateProvider;
import com.np.pricehunt.backend.tenancy.TestTenants;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
 * The invitation gate end to end (#249): real chain, real decoder fed by the fake identity provider, real
 * Postgres so the partial unique index and the row lock are the ones production has. Every case asserts
 * database state after the request, because "uninvited login provisions nothing" is a claim about rows.
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
        })
class InvitationRouteTest {

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
    private String admin;
    private String member;

    @BeforeEach
    void seed() {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        jdbc.update("DELETE FROM invitation");
        appUsers.deleteAll();
        TestTenants.admit(appUsers, "auth0|admin");
        TestTenants.admit(appUsers, "auth0|alice");
        admin = "Bearer " + IDP.adminToken("auth0|admin");
        member = "Bearer " + IDP.userToken("auth0|alice");
    }

    private static String guest(String email, boolean verified) {
        return "Bearer " + IDP.userToken(GUEST, email, verified);
    }

    private long invite(String email) throws Exception {
        String body = mvc.perform(post("/api/admin/invitations")
                        .header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return ((Number) JsonPath.read(body, "$.id")).longValue();
    }

    private List<Map<String, Object>> invitations() {
        return jdbc.queryForList("SELECT * FROM invitation ORDER BY id");
    }

    private String statusOf(long id) throws Exception {
        String body = mvc.perform(get("/api/admin/invitations").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> statuses = JsonPath.read(body, "$[?(@.id == " + id + ")].status");
        assertThat(statuses).hasSize(1);
        return statuses.get(0);
    }

    // --- redemption ---

    @Test
    void uninvitedIdentity_is403_andProvisionsNothing() throws Exception {
        mvc.perform(post("/api/me").header(HttpHeaders.AUTHORIZATION, guest(GUEST_EMAIL, true)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("No valid invitation for this identity"));

        assertThat(appUsers.findByIssuerAndSub(FakeIdentityProvider.ISSUER, GUEST))
                .isEmpty();
    }

    @Test
    void unverifiedEmail_is403_evenWithAnInvitation_andConsumesNothing() throws Exception {
        invite(GUEST_EMAIL);

        mvc.perform(post("/api/me").header(HttpHeaders.AUTHORIZATION, guest(GUEST_EMAIL, false)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail")
                        .value("Verify the email address with your sign-in provider, then sign out and back in"));

        assertThat(appUsers.findByIssuerAndSub(FakeIdentityProvider.ISSUER, GUEST))
                .isEmpty();
        assertThat(invitations().get(0).get("redeemed_at")).isNull();
    }

    @Test
    void invitedIdentity_is201_provisionsTheAccount_andConsumesTheInvitation() throws Exception {
        long id = invite(" Guest@Example.com ");

        // The token spells the address differently again: the match is on the normalized form.
        mvc.perform(post("/api/me").header(HttpHeaders.AUTHORIZATION, guest("GUEST@example.com", true)))
                .andExpect(status().isCreated());

        AppUser guest =
                appUsers.findByIssuerAndSub(FakeIdentityProvider.ISSUER, GUEST).orElseThrow();
        assertThat(guest.getEmail()).isEqualTo(GUEST_EMAIL);
        Map<String, Object> row = invitations().get(0);
        assertThat(row.get("email")).isEqualTo(GUEST_EMAIL);
        assertThat(row.get("redeemed_at")).isNotNull();
        assertThat(((Number) row.get("redeemed_by")).longValue()).isEqualTo(guest.getId());
        assertThat(statusOf(id)).isEqualTo("REDEEMED");

        // Now admitted: the account route answers, and a repeat POST is a no-op.
        mvc.perform(get("/api/me").header(HttpHeaders.AUTHORIZATION, guest(GUEST_EMAIL, true)))
                .andExpect(status().isOk());
        mvc.perform(post("/api/me").header(HttpHeaders.AUTHORIZATION, guest(GUEST_EMAIL, true)))
                .andExpect(status().isNoContent());
        assertThat(appUsers.count()).isEqualTo(3);
    }

    @Test
    void anInvitationIsSingleUse_aSecondIdentityWithTheSameEmail_is403() throws Exception {
        invite(GUEST_EMAIL);
        mvc.perform(post("/api/me").header(HttpHeaders.AUTHORIZATION, guest(GUEST_EMAIL, true)))
                .andExpect(status().isCreated());

        String other = "Bearer " + IDP.userToken("google-oauth2|guest", GUEST_EMAIL, true);
        mvc.perform(post("/api/me").header(HttpHeaders.AUTHORIZATION, other)).andExpect(status().isForbidden());

        assertThat(appUsers.findByIssuerAndSub(FakeIdentityProvider.ISSUER, "google-oauth2|guest"))
                .isEmpty();
    }

    @Test
    void anExpiredInvitation_is403() throws Exception {
        long id = invite(GUEST_EMAIL);
        jdbc.update("UPDATE invitation SET expires_at = now() - interval '1 minute' WHERE id = ?", id);

        mvc.perform(post("/api/me").header(HttpHeaders.AUTHORIZATION, guest(GUEST_EMAIL, true)))
                .andExpect(status().isForbidden());

        assertThat(appUsers.findByIssuerAndSub(FakeIdentityProvider.ISSUER, GUEST))
                .isEmpty();
        assertThat(statusOf(id)).isEqualTo("EXPIRED");
    }

    @Test
    void aRevokedInvitation_is403() throws Exception {
        long id = invite(GUEST_EMAIL);
        mvc.perform(delete("/api/admin/invitations/" + id).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/me").header(HttpHeaders.AUTHORIZATION, guest(GUEST_EMAIL, true)))
                .andExpect(status().isForbidden());

        assertThat(appUsers.findByIssuerAndSub(FakeIdentityProvider.ISSUER, GUEST))
                .isEmpty();
        assertThat(statusOf(id)).isEqualTo("REVOKED");
    }

    // --- admin management ---

    @Test
    void adminRoutes_needTheAdminRole_notJustAdmission() throws Exception {
        mvc.perform(get("/api/admin/invitations").header(HttpHeaders.AUTHORIZATION, member))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/admin/invitations")
                        .header(HttpHeaders.AUTHORIZATION, member)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"x@example.com\"}"))
                .andExpect(status().isForbidden());
        assertThat(invitations()).isEmpty();
    }

    @Test
    void invite_recordsTheInviter_andListsAsPending() throws Exception {
        long id = invite(GUEST_EMAIL);

        Map<String, Object> row = invitations().get(0);
        long adminId = appUsers.findByIssuerAndSub(FakeIdentityProvider.ISSUER, "auth0|admin")
                .orElseThrow()
                .getId();
        assertThat(((Number) row.get("invited_by")).longValue()).isEqualTo(adminId);
        assertThat(row.get("expires_at")).isNotNull();
        assertThat(statusOf(id)).isEqualTo("PENDING");
    }

    @Test
    void reinviting_supersedesTheOpenInvitation() throws Exception {
        long first = invite(GUEST_EMAIL);
        long second = invite(GUEST_EMAIL);

        assertThat(statusOf(first)).isEqualTo("REVOKED");
        assertThat(statusOf(second)).isEqualTo("PENDING");
        // The superseded row can no longer be redeemed; the new one can.
        mvc.perform(post("/api/me").header(HttpHeaders.AUTHORIZATION, guest(GUEST_EMAIL, true)))
                .andExpect(status().isCreated());
        assertThat(statusOf(second)).isEqualTo("REDEEMED");
    }

    @Test
    void revoke_isIdempotent_andLeavesARedeemedInvitationRedeemed() throws Exception {
        long id = invite(GUEST_EMAIL);
        mvc.perform(delete("/api/admin/invitations/" + id).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/invitations/" + id).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
        assertThat(statusOf(id)).isEqualTo("REVOKED");

        long redeemed = invite(GUEST_EMAIL);
        mvc.perform(post("/api/me").header(HttpHeaders.AUTHORIZATION, guest(GUEST_EMAIL, true)))
                .andExpect(status().isCreated());
        mvc.perform(delete("/api/admin/invitations/" + redeemed).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNoContent());
        assertThat(statusOf(redeemed)).isEqualTo("REDEEMED");
        assertThat(invitations().get(1).get("revoked_at")).isNull();

        mvc.perform(delete("/api/admin/invitations/999999").header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(status().isNotFound());
    }

    @Test
    void aRedeemedInvitation_staysRedeemed_afterItsWindowCloses() throws Exception {
        long id = invite(GUEST_EMAIL);
        mvc.perform(post("/api/me").header(HttpHeaders.AUTHORIZATION, guest(GUEST_EMAIL, true)))
                .andExpect(status().isCreated());
        jdbc.update("UPDATE invitation SET expires_at = now() - interval '1 minute' WHERE id = ?", id);

        assertThat(statusOf(id)).isEqualTo("REDEEMED");
    }

    @Test
    void invite_rejectsAMissingOrMalformedEmail() throws Exception {
        for (String body : List.of("null", "{}", "{\"email\":\"  \"}", "{\"email\":\"not-an-address\"}")) {
            mvc.perform(post("/api/admin/invitations")
                            .header(HttpHeaders.AUTHORIZATION, admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest());
        }
        assertThat(invitations()).isEmpty();
    }
}
