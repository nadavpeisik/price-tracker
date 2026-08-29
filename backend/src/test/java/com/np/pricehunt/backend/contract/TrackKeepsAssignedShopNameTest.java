package com.np.pricehunt.backend.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.np.pricehunt.backend.client.ScraperClient;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.service.fx.FxRateProvider;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The shop name assigned during a first track must survive that same request (issue #221).
 *
 * <p>The name is written by id, in its own short transactions, before the price is persisted; the
 * price step then loads the {@code TrackedItem} entity and flushes it. If a persistence context
 * outlives those transactions (Spring Boot's default {@code open-in-view=true} keeps one open for
 * the whole request), that load is a first-level-cache hit on the pre-name instance and the flush
 * writes {@code shop_name = NULL} back over the committed name. Only a full request through the
 * real {@code EntityManager} can see this — the service unit tests mock the repositories and have
 * no cache to be stale — so this runs the real web + JPA stack on Testcontainers, with no outer
 * test transaction, and checks the stored row through JDBC so the cache cannot vouch for itself.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(
        properties = {
            "spring.docker.compose.enabled=false",
            "price.scheduler.enabled=false",
            // Shadows ${GROQ_API_KEY} (#121) so the context boots with no secret; extraction is never called here.
            "spring.ai.openai.api-key=test-key",
            "pricehunt.currency.fx.refresh-cron=-",
            "scrape.audit.purge-cron=-",
        })
class TrackKeepsAssignedShopNameTest {

    /** Not a curated domain, so a strong proposal is learned and re-resolves to MAPPING. */
    private static final String URL = "https://bestbuy.com/site/p/6TEST.p";

    private static final String PAGE_SITE_NAME = "Best Buy";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ScraperClient scraperClient;

    /** Mocked so the empty container never triggers a network FX refresh; see the sibling test. */
    @MockitoBean
    private FxRateProvider rateProvider;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        productRepository.deleteAll();
        // STRUCTURED short-circuits the waterfall (no LLM), and a successful price is what makes the
        // persist step load + flush the entity — the write that can clobber the name.
        when(scraperClient.scrape(anyString()))
                .thenReturn(new ScrapeResponse(
                        ExtractionSource.STRUCTURED,
                        new ScrapeResponse.PriceData(new BigDecimal("199.99"), "USD", AvailabilityStatus.AVAILABLE),
                        null,
                        null,
                        null,
                        new ScrapeResponse.ShopNameProposal(PAGE_SITE_NAME, true)));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void firstTrackReportsAndStoresTheShopNameItAssigned() throws Exception {
        Product product =
                productRepository.save(Product.builder().name("Sony WH-1000XM5").build());

        String body = mvc.perform(post("/api/products/{id}/track", product.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"" + URL + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shopName").value(PAGE_SITE_NAME))
                .andExpect(jsonPath("$.shopNameSource").value("MAPPING"))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long trackedItemId = ((Number) JsonPath.read(body, "$.trackedItemId")).longValue();

        Map<String, Object> row = jdbc.queryForMap(
                "select shop_name, shop_name_source, last_checked from tracked_item where id = ?", trackedItemId);
        assertThat(row.get("shop_name")).isEqualTo(PAGE_SITE_NAME);
        assertThat(row.get("shop_name_source")).isEqualTo("MAPPING");
        assertThat(row.get("last_checked"))
                .as("the price step did run and flush")
                .isNotNull();
    }
}
