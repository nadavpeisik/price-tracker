package com.np.pricehunt.backend.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * One amount, one spelling — across the two endpoints that see it at different points in its life
 * (issue #175).
 *
 * <p>This is the test the fixed-scale decision exists for. {@code POST /track} answers from a record
 * that has <em>not</em> round-tripped Postgres and carries whatever scale the extractor produced,
 * while price-history reads the same row back out of {@code numeric(19,4)}. Formatting at the source's
 * own scale would make one price render two ways depending on which call you made.
 *
 * <p>It runs on Testcontainers rather than H2 because the whole argument is about how PostgreSQL
 * {@code numeric(19,4)} rounds — H2 fakes that inconsistently. The scraper is mocked with a STRUCTURED
 * response, which short-circuits the extraction waterfall before any LLM call, so no Ollama is needed.
 */
@SpringBootTest
@Testcontainers
@TestPropertySource(
        properties = {
            "spring.docker.compose.enabled=false",
            "price.scheduler.enabled=false",
            "spring.ai.ollama.init.pull-model-strategy=never",
            "pricehunt.currency.fx.refresh-cron=-",
            "scrape.audit.purge-cron=-",
        })
class TrackAndHistoryAgreeOnPriceTest {

    /** Five decimals, so the column must round it and the response cannot simply echo the input. */
    private static final BigDecimal OVER_SCALE_PRICE = new BigDecimal("1234.56785");

    private static final String EXPECTED = "1234.5679";

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

    @PersistenceContext
    private EntityManager em;

    @MockitoBean
    private ScraperClient scraperClient;

    /**
     * Unused by this test, but mocked so it cannot reach the network: the container starts with an
     * empty database, so {@code ExchangeRateService}'s ApplicationReadyEvent listener finds no stored
     * snapshot and calls {@code refresh()}. Left unstubbed on purpose — {@code refresh()} swallows the
     * null, and nothing here converts currencies.
     */
    @MockitoBean
    private FxRateProvider rateProvider;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        productRepository.deleteAll();
        when(scraperClient.scrape(anyString()))
                .thenReturn(new ScrapeResponse(
                        ExtractionSource.STRUCTURED,
                        new ScrapeResponse.PriceData(OVER_SCALE_PRICE, "USD", AvailabilityStatus.AVAILABLE),
                        null,
                        null,
                        null));
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void trackAndPriceHistoryReportTheSameStringAsTheColumnStores() throws Exception {
        Product product =
                productRepository.save(Product.builder().name("Keychron K8 Pro").build());

        String trackBody = mvc.perform(post("/api/products/{id}/track", product.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":\"https://bestbuy.com/site/p/6TEST.p\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentPrice").value(EXPECTED))
                .andReturn()
                .getResponse()
                .getContentAsString();
        long trackedItemId = ((Number) JsonPath.read(trackBody, "$.trackedItemId")).longValue();

        // Clear first: without it the second read could be served the same managed entity the write
        // left behind, and would prove nothing about what the column actually holds.
        em.clear();

        mvc.perform(get("/api/products/{id}/tracked-items/{itemId}/price-history", product.getId(), trackedItemId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.history[0].price").value(EXPECTED));

        BigDecimal stored =
                (BigDecimal) em.createNativeQuery("select price from price_record where tracked_item_id = :id")
                        .setParameter("id", trackedItemId)
                        .getSingleResult();
        assertThat(stored.toPlainString()).isEqualTo(EXPECTED);
    }
}
