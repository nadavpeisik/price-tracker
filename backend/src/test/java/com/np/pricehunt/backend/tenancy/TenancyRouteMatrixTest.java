package com.np.pricehunt.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.repository.AppUserRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.repository.UserProductRepository;
import com.np.pricehunt.backend.service.fx.FxRateProvider;
import com.np.pricehunt.backend.validator.HostResolver;
import java.net.InetAddress;
import java.time.Instant;
import java.util.List;
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
import org.springframework.web.client.RestClientException;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Two users, one shared catalog, the real filter chain, real Postgres (#246). A tracks P1 and P2, B
 * tracks P1 and P3, nobody tracks P4. Every user-facing route is exercised as A against B's rows and
 * the database state is asserted after every mutation, not just the status — the whole point of the
 * scoped layer is that a valid id is not enough.
 *
 * <p>Tokens come from the fake identity provider so decoding, admission and tenancy are proven
 * together here; the other integration tests mock {@code CurrentUser} and stay focused on their own
 * subject.
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
class TenancyRouteMatrixTest {

    private static final FakeIdentityProvider IDP = FakeIdentityProvider.start();

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
    private ProductRepository productRepository;

    @Autowired
    private TrackedItemRepository trackedItemRepository;

    @Autowired
    private AppUserRepository appUsers;

    @Autowired
    private UserProductRepository memberships;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ScraperClient scraperClient;

    @MockitoBean
    private FxRateProvider rateProvider;

    /** Public-looking hosts without touching DNS: the SSRF check resolves every name to a public address. */
    @MockitoBean
    private HostResolver hostResolver;

    private MockMvc mvc;
    private AppUser alice;
    private AppUser bob;
    private Product p1Shared;
    private Product p2AliceOnly;
    private Product p3BobOnly;
    private Product p4Nobody;
    private TrackedItem l2UnderP1AddedByBob;
    private TrackedItem l3UnderP2;
    private TrackedItem l4UnderP3;

    @BeforeEach
    void seed() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
        productRepository.deleteAll();
        when(hostResolver.resolve(anyString())).thenReturn(new InetAddress[] {InetAddress.getByName("93.184.216.34")});
        // A null scrape is the cheapest successful check: no extraction, no price, no LLM.
        when(scraperClient.scrape(anyString())).thenReturn(null);

        alice = TestTenants.admit(appUsers, "auth0|alice");
        bob = TestTenants.admit(appUsers, "auth0|bob");
        TestTenants.admit(appUsers, "auth0|admin");

        p1Shared = product("Sony WH-1000XM5");
        p2AliceOnly = product("Keychron K8 Pro");
        p3BobOnly = product("Dyson V15");
        p4Nobody = product("Nobody's lamp");
        listing(p1Shared, "KSP", 1);
        l2UnderP1AddedByBob = listing(p1Shared, "Bug", 2);
        l3UnderP2 = listing(p2AliceOnly, "Ivory", 3);
        l4UnderP3 = listing(p3BobOnly, "TMS", 4);
        listing(p4Nobody, "Electra", 5);
        TestTenants.track(memberships, alice, p1Shared);
        TestTenants.track(memberships, bob, p1Shared);
        TestTenants.track(memberships, alice, p2AliceOnly);
        TestTenants.track(memberships, bob, p3BobOnly);
    }

    // --- reads ---

    @Test
    void dashboard_showsOnlyTheCallersProducts_withEveryShopUnderThem() throws Exception {
        String body = mvc.perform(get("/api/tracked-products").header(HttpHeaders.AUTHORIZATION, as("auth0|alice")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<String> names = JsonPath.read(body, "$.items[*].name");
        assertThat(names).containsExactly("Keychron K8 Pro", "Sony WH-1000XM5");
        assertThat((int) JsonPath.read(body, "$.globalSummary.totalTracked")).isEqualTo(2);
        // B added the Bug listing under the shared product; A sees it — that is the shared catalog.
        List<String> shops = JsonPath.read(body, "$.facets.shops[*]");
        assertThat(shops).containsExactlyInAnyOrder("KSP", "Bug", "Ivory");
    }

    @Test
    void everyProductRead_is404_forAProductTheCallerDoesNotTrack() throws Exception {
        for (String path : List.of(
                "/api/products/" + p3BobOnly.getId(),
                "/api/products/" + p3BobOnly.getId() + "/listings",
                "/api/products/" + p3BobOnly.getId() + "/price-trend",
                "/api/products/" + p3BobOnly.getId() + "/tracked-items/" + l4UnderP3.getId() + "/price-history",
                // A valid item of A's own, but under a foreign product id in the path.
                "/api/products/" + p3BobOnly.getId() + "/tracked-items/" + l3UnderP2.getId() + "/price-history",
                // A product nobody tracks reads the same as one somebody else tracks.
                "/api/products/" + p4Nobody.getId())) {
            mvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, as("auth0|alice")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status").value(404));
        }
        // And the same paths answer for the user who does track it.
        mvc.perform(get("/api/products/" + p3BobOnly.getId()).header(HttpHeaders.AUTHORIZATION, as("auth0|bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Dyson V15"));
    }

    // --- mutations ---

    @Test
    void refreshingAnotherUsersListing_is404_andTouchesNothing() throws Exception {
        mvc.perform(post("/api/products/{p}/tracked-items/{i}/refresh", p3BobOnly.getId(), l4UnderP3.getId())
                        .header(HttpHeaders.AUTHORIZATION, as("auth0|alice")))
                .andExpect(status().isNotFound());

        verify(scraperClient, never()).scrape(anyString());
        assertThat(jdbc.queryForObject(
                        "SELECT last_checked FROM tracked_item WHERE id = ?", Instant.class, l4UnderP3.getId()))
                .isNull();
    }

    @Test
    void stopTracking_removesOnlyTheCallersMembership_andNeverTheCatalogRow() throws Exception {
        // Not A's: 404, and B's row is untouched.
        mvc.perform(delete("/api/tracked-products/{p}", p3BobOnly.getId())
                        .header(HttpHeaders.AUTHORIZATION, as("auth0|alice")))
                .andExpect(status().isNotFound());
        assertThat(membershipCount(bob, p3BobOnly)).isEqualTo(1);

        // Shared: A's row goes, B's stays, the product and its listings stay.
        mvc.perform(delete("/api/tracked-products/{p}", p1Shared.getId())
                        .header(HttpHeaders.AUTHORIZATION, as("auth0|alice")))
                .andExpect(status().isNoContent());
        assertThat(membershipCount(alice, p1Shared)).isZero();
        assertThat(membershipCount(bob, p1Shared)).isEqualTo(1);
        assertThat(productRepository.existsById(p1Shared.getId())).isTrue();
        assertThat(trackedItemRepository.existsById(l2UnderP1AddedByBob.getId()))
                .isTrue();

        String body = mvc.perform(get("/api/tracked-products").header(HttpHeaders.AUTHORIZATION, as("auth0|alice")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<String>>read(body, "$.items[*].name")).containsExactly("Keychron K8 Pro");
    }

    @Test
    void trackingAUrlUnderSomeoneElsesProduct_attachesTheCaller_andReTrackingKeepsAddedAt() throws Exception {
        // The one deliberately catalog-global route: attaching to a shared product is the feature.
        mvc.perform(post("/api/products/{p}/track", p3BobOnly.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + l4UnderP3.getUrl() + "\"}")
                        .header(HttpHeaders.AUTHORIZATION, as("auth0|alice")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(p3BobOnly.getId()));
        assertThat(membershipCount(alice, p3BobOnly)).isEqualTo(1);
        Instant addedAt = addedAt(alice, p3BobOnly);
        // Reusing the listing admitted nothing new.
        assertThat(trackedItemRepository.findByProduct(p3BobOnly)).hasSize(1);

        mvc.perform(post("/api/products/{p}/track", p3BobOnly.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://another-shop.com/dyson\"}")
                        .header(HttpHeaders.AUTHORIZATION, as("auth0|alice")))
                .andExpect(status().isOk());
        assertThat(membershipCount(alice, p3BobOnly)).isEqualTo(1);
        assertThat(addedAt(alice, p3BobOnly)).isEqualTo(addedAt);
        assertThat(trackedItemRepository.findByProduct(p3BobOnly)).hasSize(2);
    }

    @Test
    void aFailedFirstScrape_keepsTheListingAndTheMembership_withNoPrice() throws Exception {
        // Blocked sites and scraper outages are routine; "I want to track this" must survive them.
        when(scraperClient.scrape(anyString())).thenThrow(new RestClientException("scraper unreachable"));

        mvc.perform(post("/api/products/{p}/track", p4Nobody.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://new-shop.com/lamp\"}")
                        .header(HttpHeaders.AUTHORIZATION, as("auth0|alice")))
                .andExpect(status().isBadGateway());

        assertThat(membershipCount(alice, p4Nobody)).isEqualTo(1);
        List<TrackedItem> listings = trackedItemRepository.findByProduct(p4Nobody);
        assertThat(listings).extracting(TrackedItem::getUrl).contains("https://new-shop.com/lamp");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM price_record", Integer.class))
                .isZero();
    }

    @Test
    void creatingAProduct_tracksItForTheCreatorOnly() throws Exception {
        String body = mvc.perform(post("/api/products")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Alice's new thing\"}")
                        .header(HttpHeaders.AUTHORIZATION, as("auth0|alice")))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        long created = ((Number) JsonPath.read(body, "$.id")).longValue();

        mvc.perform(get("/api/products/{p}", created).header(HttpHeaders.AUTHORIZATION, as("auth0|alice")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/products/{p}", created).header(HttpHeaders.AUTHORIZATION, as("auth0|bob")))
                .andExpect(status().isNotFound());
        String bobsDashboard = mvc.perform(
                        get("/api/tracked-products").header(HttpHeaders.AUTHORIZATION, as("auth0|bob")))
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<String>>read(bobsDashboard, "$.items[*].name"))
                .doesNotContain("Alice's new thing");
    }

    @Test
    void adminHardDelete_removesTheProductForEveryone_cascadingBothMemberships() throws Exception {
        // The gate itself (user 403 / admin passes) is SecurityPostureTest's; this is the tenancy state.
        mvc.perform(delete("/api/products/{p}", p1Shared.getId())
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + IDP.adminToken("auth0|admin")))
                .andExpect(status().isNoContent());

        assertThat(productRepository.existsById(p1Shared.getId())).isFalse();
        assertThat(membershipCount(alice, p1Shared)).isZero();
        assertThat(membershipCount(bob, p1Shared)).isZero();
        // Their other memberships are untouched.
        assertThat(membershipCount(alice, p2AliceOnly)).isEqualTo(1);
        assertThat(membershipCount(bob, p3BobOnly)).isEqualTo(1);
    }

    // --- fixtures ---

    private String as(String sub) {
        return "Bearer " + IDP.userToken(sub);
    }

    private Product product(String name) {
        return productRepository.save(Product.builder().name(name).build());
    }

    private TrackedItem listing(Product product, String shop, int n) {
        return trackedItemRepository.save(TrackedItem.builder()
                .url("https://shop-" + n + ".com/item/" + n)
                .shopName(shop)
                .product(product)
                .build());
    }

    private int membershipCount(AppUser user, Product product) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_product WHERE user_id = ? AND product_id = ?",
                Integer.class,
                user.getId(),
                product.getId());
    }

    private Instant addedAt(AppUser user, Product product) {
        return jdbc.queryForObject(
                        "SELECT added_at FROM user_product WHERE user_id = ? AND product_id = ?",
                        java.sql.Timestamp.class,
                        user.getId(),
                        product.getId())
                .toInstant();
    }
}
