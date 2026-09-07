package com.np.pricehunt.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.np.pricehunt.backend.domain.AppUser;
import com.np.pricehunt.backend.repository.AppUserRepository;
import com.np.pricehunt.backend.service.fx.FxRateProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The security posture of the whole application, end to end through the real filter chain, Boot's real
 * decoder and the real advice (issue #245): every mapping is anonymous-401, every {@code /api} mapping
 * is unknown-identity-403, the token validators are live, the role and admission gates hold, and the
 * rejection bodies are the {@code ProblemDetail} contract.
 *
 * <p>Enumeration, not a hand-picked list, so a newly added controller is covered the moment it exists
 * and there is no allow-list to forget. The actuator is unreachable from a MOCK environment, because its
 * child context only exists on a real server and Boot refuses a management address on a shared port, so
 * {@link ManagementPortPostureTest} pins those rules instead.
 */
@SpringBootTest
@ActiveProfiles({"test", "dev"})
@AutoConfigureMockMvc
@TestPropertySource(
        properties = {
            "spring.docker.compose.enabled=false",
            "price.scheduler.enabled=false",
            "pricehunt.currency.fx.refresh-cron=-",
            "scrape.audit.purge-cron=-",
            // The dev export controller must exist so its ADMIN gate is enumerated and exercised.
            "scrape.audit.export-enabled=true",
        })
class SecurityPostureTest {

    private static final FakeIdentityProvider IDP = FakeIdentityProvider.start();

    private static final String UNKNOWN_SUB = "auth0|nobody";
    private static final String PROBLEM_JSON = "application/problem+json";

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", IDP::jwkSetUri);
    }

    @AfterAll
    static void stopIdentityProvider() {
        IDP.stop();
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private RequestMappingHandlerMapping mappings;

    @Autowired
    private AppUserRepository appUsers;

    @MockitoBean
    private FxRateProvider fxRateProvider;

    @BeforeEach
    void admitTheDefaultIdentity() {
        // Idempotent: the context is shared and not transactional, so a second save would trip
        // uq_app_user_identity.
        appUsers.findByIssuerAndSub(FakeIdentityProvider.ISSUER, FakeIdentityProvider.DEFAULT_SUB)
                .orElseGet(() -> appUsers.save(AppUser.builder()
                        .issuer(FakeIdentityProvider.ISSUER)
                        .sub(FakeIdentityProvider.DEFAULT_SUB)
                        .build()));
    }

    // --- enumeration: every mapping, no exclusion list ---

    /** One concrete request per declared (method, pattern) pair. */
    private record Route(HttpMethod method, String path) {
        MockHttpServletRequestBuilder request() {
            return MockMvcRequestBuilders.request(method, path);
        }

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    private List<Route> allRoutes() {
        List<Route> routes = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry :
                mappings.getHandlerMethods().entrySet()) {
            RequestMappingInfo mapping = entry.getKey();
            Set<String> patterns = mapping.getPathPatternsCondition() == null
                    ? Set.of()
                    : mapping.getPathPatternsCondition().getPatternValues();
            var methods = mapping.getMethodsCondition().getMethods();
            for (String pattern : patterns) {
                // Every path variable in this API is a numeric id. A future slug-shaped one needs a
                // per-name substitution here rather than a mysterious 400.
                String path = pattern.replaceAll("\\{[^}]+}", "1");
                if (methods.isEmpty()) {
                    routes.add(new Route(HttpMethod.GET, path));
                } else {
                    methods.forEach(m -> routes.add(new Route(HttpMethod.valueOf(m.name()), path)));
                }
            }
        }
        assertThat(routes)
                .describedAs("mapping enumeration found nothing - the guard would pass vacuously")
                .isNotEmpty();
        assertThat(routes.stream().map(Route::toString))
                .describedAs("one sentinel per controller, so the enumeration is known to reach each")
                .contains("POST /api/products", "GET /api/tracked-products", "GET /api/dev/scrape-attempts/1/fixture");
        return routes;
    }

    @Test
    void everyMapping_anonymously_is401ProblemDetail() throws Exception {
        for (Route route : allRoutes()) {
            mvc.perform(route.request())
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer"))
                    .andExpect(jsonPath("$.status").value(401));
        }
    }

    @Test
    void everyApiMapping_withAValidButUnknownIdentity_is403ProblemDetail() throws Exception {
        // Admission: a valid token from the tenant is not an account. #249 adds its one exception
        // (the invitation-redemption route) to this loop when it exists.
        for (Route route : allRoutes()) {
            if (!route.path().startsWith("/api/")) {
                continue;
            }
            mvc.perform(route.request().header(HttpHeaders.AUTHORIZATION, bearer(IDP.adminToken(UNKNOWN_SUB))))
                    .andExpect(status().isForbidden())
                    .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                    .andExpect(header().string(
                                    HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer error=\"insufficient_scope\"")))
                    .andExpect(jsonPath("$.status").value(403));
        }
    }

    // --- the token validators are live: Boot's real decoder against the fixture's JWKS ---

    @Test
    void garbageBearer_is401InvalidToken() throws Exception {
        mvc.perform(get("/api/products/1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(FakeIdentityProvider.malformedToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer error=\"invalid_token\"")));
    }

    @Test
    void wrongAudience_is401() throws Exception {
        // The one validator Boot does not install by default: proves the audiences property is live.
        mvc.perform(get("/api/products/1").header(HttpHeaders.AUTHORIZATION, bearer(IDP.wrongAudienceToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void wrongIssuer_is401() throws Exception {
        mvc.perform(get("/api/products/1").header(HttpHeaders.AUTHORIZATION, bearer(IDP.wrongIssuerToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredToken_is401() throws Exception {
        mvc.perform(get("/api/products/1").header(HttpHeaders.AUTHORIZATION, bearer(IDP.expiredToken())))
                .andExpect(status().isUnauthorized());
    }

    // --- filter-provided routes and statelessness ---

    @Test
    void logout_isNotARoute_anonymousGetIs401NotARedirect() throws Exception {
        // Spring's default LogoutFilter would answer with a 302; it is disabled in SecurityConfig.
        mvc.perform(get("/logout"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void protectedResourceMetadata_isTheOneOtherAnonymousRoute_andNamesNoIdentityProvider() throws Exception {
        // Spring Security 7 installs OAuth2ProtectedResourceMetadataFilter ahead of AuthorizationFilter
        // and it short-circuits, so no authorization rule can reach it and there is no DSL toggle. RFC
        // 9728 means it to be public: a client reads it to learn HOW to authenticate. Pinned rather than
        // fought, so an upgrade that starts publishing authorization_servers -- our tenant URL -- to
        // anonymous callers fails here.
        String body = mvc.perform(get("/.well-known/oauth-protected-resource"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("test-issuer.invalid");
    }

    @Test
    void noRequest_createsASessionOrSetsACookie() throws Exception {
        MvcResult rejected = mvc.perform(get("/api/products/1")).andReturn();
        MvcResult admitted = mvc.perform(
                        get("/api/products/999999").header(HttpHeaders.AUTHORIZATION, bearer(IDP.userToken())))
                .andReturn();
        for (MvcResult result : List.of(rejected, admitted)) {
            assertThat(result.getResponse().getHeader(HttpHeaders.SET_COOKIE)).isNull();
            assertThat(result.getRequest().getSession(false)).isNull();
        }
    }

    // --- admission and roles ---

    @Test
    void admittedUser_passesTheGate() throws Exception {
        // 404 from the service is the proof that the request reached the controller. The product
        // endpoint is the probe because the dashboard query is Postgres-native and cannot run on H2.
        mvc.perform(get("/api/products/999999").header(HttpHeaders.AUTHORIZATION, bearer(IDP.userToken())))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON));
    }

    @Test
    void devExport_needsAdminAndAdmission() throws Exception {
        String devRoute = "/api/dev/scrape-attempts/1/fixture";
        mvc.perform(get(devRoute).header(HttpHeaders.AUTHORIZATION, bearer(IDP.userToken())))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(PROBLEM_JSON))
                .andExpect(header().string(
                                HttpHeaders.WWW_AUTHENTICATE, startsWith("Bearer error=\"insufficient_scope\"")));
        mvc.perform(get(devRoute).header(HttpHeaders.AUTHORIZATION, bearer(IDP.adminToken(UNKNOWN_SUB))))
                .andExpect(status().isForbidden());
        // Admitted admin: past the gate (the service then 404s the unknown attempt id).
        mvc.perform(get(devRoute).header(HttpHeaders.AUTHORIZATION, bearer(IDP.adminToken())))
                .andExpect(status().isNotFound());
    }

    @Test
    void rejection_stillCarriesTheCorrelationId() throws Exception {
        // CorrelationIdFilter runs ahead of the security chain, so even a 401 is traceable.
        mvc.perform(get("/api/products/1").header("X-Correlation-ID", "posture-test"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Correlation-ID", "posture-test"));
    }

    @Test
    void problemBody_isTheAdviceShape_notSpringSecuritysDefault() throws Exception {
        mvc.perform(get("/api/products/1").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.detail").value("Authentication required"));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
