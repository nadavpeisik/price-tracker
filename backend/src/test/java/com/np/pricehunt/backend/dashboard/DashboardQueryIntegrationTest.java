package com.np.pricehunt.backend.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExchangeRate;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.ShopNameSource;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.repository.ExchangeRateRepository;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.service.fx.ExchangeRateService;
import com.np.pricehunt.backend.service.fx.FxRateProvider;
import com.np.pricehunt.backend.service.fx.RateSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
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
 * The dashboard endpoint end to end, against the wiring it actually ships with (issue #146).
 *
 * <p><b>Real Postgres, not H2.</b> The two-cutoff query is native SQL with quoted projection aliases,
 * and H2 folds unquoted identifiers to upper case while Postgres folds them to lower — a query written
 * for one would have to be written differently for the other, and the one that runs in production is
 * the one worth testing. Testcontainers also means Flyway runs every migration with
 * {@code ddl-auto=validate}, so this is a second gate on the migration.
 *
 * <p><b>What it is really for: equivalence.</b> The dashboard's lean pass and the trend endpoint
 * reach the same numbers by two different fetches. Sharing the calculator makes that true by
 * construction for the maths; nothing but a test makes it true for the <em>selection</em>. So every
 * fixture below is asserted twice — once through {@code /api/tracked-products} and once through that
 * product's {@code /price-trend} — across exactly the cases where a naive query would diverge:
 * an UNAVAILABLE latest observation, a TTL-expired one, tied timestamps, mixed currencies, a missing
 * baseline, and a stale rate.
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
class DashboardQueryIntegrationTest {

    private static final String ILS = "ILS";
    private static final String USD = "USD";
    private static final Instant FIXED_NOW = Instant.parse("2026-03-20T12:00:00Z");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    /** Production reads "now" from the application {@link Clock}; fixtures must read the same one. */
    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(FIXED_NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TrackedItemRepository trackedItemRepository;

    @Autowired
    private PriceRecordRepository priceRecordRepository;

    @Autowired
    private ExchangeRateRepository exchangeRateRepository;

    @Autowired
    private ExchangeRateService rateService;

    @MockitoBean
    private FxRateProvider rateProvider;

    private MockMvc mvc;
    private LocalDate today;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        today = LocalDate.ofInstant(FIXED_NOW, ZoneOffset.UTC);

        productRepository.deleteAll();
        exchangeRateRepository.deleteAll();

        // @MockitoBean can't be stubbed before ApplicationReadyEvent, so the startup refresh hit an
        // unstubbed mock and persisted nothing. Install the deterministic snapshot now, explicitly.
        when(rateProvider.fetchLatest())
                .thenReturn(new RateSnapshot(today, Map.of(USD, new BigDecimal("1.10"), ILS, new BigDecimal("4.00"))));
        rateService.refresh();

        // From yesterday back: today's row already exists, written by the refresh above, and the
        // (quote, as_of) uniqueness would reject a second one.
        for (int day = 1; day <= 20; day++) {
            seedRate(USD, today.minusDays(day), "1.10");
            seedRate(ILS, today.minusDays(day), "4.00");
        }
    }

    // --- the envelope ---

    @Test
    void emptyCatalogueReturnsAWellFormedEnvelope() throws Exception {
        mvc.perform(get("/api/tracked-products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page.number").value(1))
                .andExpect(jsonPath("$.page.totalElements").value(0))
                .andExpect(jsonPath("$.page.totalPages").value(0))
                .andExpect(jsonPath("$.facets.shops").isEmpty())
                .andExpect(jsonPath("$.globalSummary.totalTracked").value(0))
                .andExpect(jsonPath("$.globalSummary.biggestDrop").doesNotExist());
    }

    @Test
    void crossCurrencyProduct_rowIsFxNormalizedAndFullyPopulated() throws Exception {
        Product product = seedProduct("Keychron K8 Pro");
        TrackedItem electra = seedItem(product, "אלקטרה", 1);
        TrackedItem amazon = seedItem(product, "Amazon", 2);
        seedRecord(electra, "385", ILS, hoursAgo(6));
        seedRecord(amazon, "102", USD, hoursAgo(5));
        seedRecord(electra, "420", ILS, daysAgo(8));
        seedRecord(amazon, "119", USD, daysAgo(8));

        // $102 → ₪370.9091 beats ₪385; baseline is ₪420 (the cheaper of ₪420 and $119 ≈ ₪432.73).
        mvc.perform(get("/api/tracked-products").param("displayCurrency", ILS).accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].name").value("Keychron K8 Pro"))
                .andExpect(jsonPath("$.items[0].bestPriceConverted").value("370.9091"))
                .andExpect(jsonPath("$.items[0].bestPriceConvertedCurrency").value(ILS))
                .andExpect(jsonPath("$.items[0].bestPriceOriginal").value("102.0000"))
                .andExpect(jsonPath("$.items[0].bestPriceOriginalCurrency").value(USD))
                .andExpect(jsonPath("$.items[0].bestPriceShop").value("Amazon"))
                .andExpect(jsonPath("$.items[0].mixedCurrencies").value(true))
                .andExpect(jsonPath("$.items[0].conversionStale").value(false))
                .andExpect(jsonPath("$.items[0].availability.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.items[0].availability.availableCount").value(2))
                .andExpect(jsonPath("$.items[0].availability.total").value(2))
                .andExpect(jsonPath("$.items[0].delta7d").value(-11.69))
                .andExpect(jsonPath("$.items[0].listings").doesNotExist())
                .andExpect(jsonPath("$.items[0].sparkline").isNotEmpty())
                .andExpect(jsonPath("$.facets.shops", org.hamcrest.Matchers.contains("Amazon", "אלקטרה")))
                .andExpect(jsonPath("$.globalSummary.drops7d").value(1))
                .andExpect(jsonPath("$.globalSummary.biggestDrop.productName").value("Keychron K8 Pro"));
    }

    // --- equivalence with the trend endpoint ---

    @Test
    void everyProductsDeltaMatchesItsTrendEndpoint_acrossTheAwkwardFixtures() throws Exception {
        seedTheAwkwardFixtures();

        String dashboard = mvc.perform(get("/api/tracked-products")
                        .param("displayCurrency", ILS)
                        .param("size", "100"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<Integer> ids = JsonPath.read(dashboard, "$.items[*].id");
        assertThat(ids).hasSize(6);

        int reportedDeltas = 0;
        int nullDeltas = 0;
        for (int index = 0; index < ids.size(); index++) {
            long productId = ids.get(index);
            Object rowDelta = readOrNull(dashboard, "$.items[" + index + "].delta7d");

            String trend = mvc.perform(
                            get("/api/products/{id}/price-trend", productId).param("displayCurrency", ILS))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            Object trendDelta = readOrNull(trend, "$.delta7d");

            assertThat(asDecimal(rowDelta))
                    .describedAs("delta7d for product %s", productId)
                    .isEqualTo(asDecimal(trendDelta));

            if (rowDelta == null) {
                nullDeltas++;
            } else {
                reportedDeltas++;
            }
        }

        // Without this the whole loop would pass on six matching nulls — agreement about nothing.
        assertThat(reportedDeltas).describedAs("fixtures with a real delta").isGreaterThanOrEqualTo(3);
        assertThat(nullDeltas)
                .describedAs("fixtures with no comparable baseline")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void everyProductsHeadlineMatchesItsTrendSeriesTodayPoint() throws Exception {
        seedTheAwkwardFixtures();

        String dashboard = mvc.perform(get("/api/tracked-products")
                        .param("displayCurrency", ILS)
                        .param("size", "100"))
                .andReturn()
                .getResponse()
                .getContentAsString();

        List<Integer> ids = JsonPath.read(dashboard, "$.items[*].id");
        String todayIso = today.atStartOfDay(ZoneOffset.UTC).toInstant().toString();

        int pricedRows = 0;
        int unpricedRows = 0;
        for (int index = 0; index < ids.size(); index++) {
            long productId = ids.get(index);
            Object headline = readOrNull(dashboard, "$.items[" + index + "].bestPriceConverted");

            String trend = mvc.perform(
                            get("/api/products/{id}/price-trend", productId).param("displayCurrency", ILS))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            // Deliberately today's point, not the series' LAST point: for a TTL-expired fixture the
            // series legitimately ends on an older day while the headline is correctly null. Comparing
            // against the last emitted point would assert the two disagree.
            Object todayPrice = readOrNull(trend, "$.sparkline[?(@.t == '" + todayIso + "')].price");

            assertThat(asDecimal(headline))
                    .describedAs("headline vs today's trend point for product %s", productId)
                    .isEqualTo(asDecimal(unwrapSingleton(todayPrice)));

            // Price agreement alone would pass even if the two paths credited different shops — which
            // matters for the cross-currency fixture, where the winner switches between the cutoffs.
            Object headlineShop = readOrNull(dashboard, "$.items[" + index + "].bestPriceShop");
            Object todayShop =
                    unwrapSingleton(readOrNull(trend, "$.sparkline[?(@.t == '" + todayIso + "')].bestOffer.shopName"));
            assertThat(headlineShop)
                    .describedAs("winning shop vs today's trend point for product %s", productId)
                    .isEqualTo(todayShop);

            if (headline == null) {
                unpricedRows++;
            } else {
                pricedRows++;
            }
        }

        // The unpriced row is the whole reason this compares against today's point rather than the
        // last one; assert it is actually present, or the distinction is never exercised.
        assertThat(pricedRows).describedAs("fixtures with a current best price").isGreaterThanOrEqualTo(4);
        assertThat(unpricedRows).describedAs("TTL-expired fixtures").isEqualTo(1);
    }

    // --- equivalence with the listings panel (#157) ---

    @Test
    void everyRowsBestListingIsTheFirstRowOfItsPanel_inBothDisplayCurrencies() throws Exception {
        seedThePanelFixtures();

        // Two currencies because rounding and margin can change the winner between them; the pin
        // must hold wherever the two endpoints are asked the same question.
        for (String displayCurrency : List.of(ILS, USD)) {
            String dashboard = mvc.perform(get("/api/tracked-products")
                            .param("displayCurrency", displayCurrency)
                            .param("size", "100"))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            List<Integer> ids = JsonPath.read(dashboard, "$.items[*].id");
            assertThat(ids).hasSize(8);

            int pricedRows = 0;
            for (int index = 0; index < ids.size(); index++) {
                long productId = ids.get(index);
                Object bestId = readOrNull(dashboard, "$.items[" + index + "].bestTrackedItemId");
                Object bestPrice = readOrNull(dashboard, "$.items[" + index + "].bestPriceConverted");

                String panel = mvc.perform(
                                get("/api/products/{id}/listings", productId).param("displayCurrency", displayCurrency))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

                if (bestId == null) {
                    assertThat(bestPrice).isNull();
                    // No eligible offer means every listing is either unpriced/unconvertible or out
                    // of stock — nothing the panel shows could have been the headline.
                    List<Object> converted = JsonPath.read(panel, "$[*].priceConverted");
                    List<String> availability = JsonPath.read(panel, "$[*].availability");
                    for (int i = 0; i < converted.size(); i++) {
                        assertThat(converted.get(i) == null || "UNAVAILABLE".equals(availability.get(i)))
                                .describedAs("product %s listing %d in %s", productId, i, displayCurrency)
                                .isTrue();
                    }
                    continue;
                }
                pricedRows++;

                Object firstId = readOrNull(panel, "$[0].trackedItemId");
                Object firstPrice = readOrNull(panel, "$[0].priceConverted");
                assertThat(((Number) firstId).longValue())
                        .describedAs(
                                "first panel row vs bestTrackedItemId for product %s in %s", productId, displayCurrency)
                        .isEqualTo(((Number) bestId).longValue());
                assertThat(asDecimal(firstPrice))
                        .describedAs("first panel price vs headline for product %s in %s", productId, displayCurrency)
                        .isEqualTo(asDecimal(bestPrice));
            }
            assertThat(pricedRows).describedAs("rows with a best listing").isGreaterThanOrEqualTo(5);
        }
    }

    @Test
    void panelAppliesTheRowsFreshnessRule_whileTheDetailKeepsTheRawObservation() throws Exception {
        Product product = seedProduct("Framework Laptop 16");
        TrackedItem tms = seedItem(product, "TMS", 3);
        TrackedItem ivory = seedItem(product, "Ivory", 4);
        seedRecord(tms, "8890", ILS, daysAgo(9));
        seedRecord(ivory, "9100", ILS, hoursAgo(3));

        // The panel: the 9-day-old TMS price is not current — no price, UNKNOWN, sorted last, but
        // lastChecked is still reported so "9 days ago" explains it.
        mvc.perform(get("/api/products/{id}/listings", product.getId()).param("displayCurrency", ILS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].shopName").value("Ivory"))
                .andExpect(jsonPath("$[0].priceConverted").value("9100.0000"))
                .andExpect(jsonPath("$[1].shopName").value("TMS"))
                .andExpect(jsonPath("$[1].priceOriginal").isEmpty())
                .andExpect(jsonPath("$[1].priceConverted").isEmpty())
                .andExpect(jsonPath("$[1].availability").value("UNKNOWN"));

        // The detail keeps its meaning: the raw latest observation at any age.
        mvc.perform(get("/api/products/{id}", product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackedItems[0].shopName").value("TMS"))
                .andExpect(jsonPath("$.trackedItems[0].currentPrice").value("8890.0000"))
                .andExpect(jsonPath("$.trackedItems[0].availability").value("AVAILABLE"))
                .andExpect(jsonPath("$.trackedItems[1].currentPrice").value("9100.0000"));
    }

    @Test
    void latestObservationSelection_isAtOrBeforeNowWithAnIdTiebreak_onBothDetailAndPanel() throws Exception {
        Product product = seedProduct("Selection");
        TrackedItem future = seedItem(product, "Future", 5, ShopNameSource.MAPPING);
        TrackedItem tied = seedItem(product, "Tied", 6);
        // A future-dated row (clock skew, hand insert) is skipped in favour of the earlier current one.
        seedRecord(future, "150", ILS, hoursAgo(5));
        seedRecord(future, "100", ILS, FIXED_NOW.plus(1, ChronoUnit.DAYS));
        // Equal timestamps: the later insert (higher id) wins, exactly as the dashboard query does.
        Instant tie = hoursAgo(2);
        seedRecord(tied, "260", ILS, tie);
        seedRecord(tied, "250", ILS, tie);

        mvc.perform(get("/api/products/{id}", product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.trackedItems[0].shopName").value("Future"))
                .andExpect(jsonPath("$.trackedItems[0].shopNameSource").value("MAPPING"))
                .andExpect(jsonPath("$.trackedItems[0].currentPrice").value("150.0000"))
                .andExpect(jsonPath("$.trackedItems[1].currentPrice").value("250.0000"));

        mvc.perform(get("/api/products/{id}/listings", product.getId()).param("displayCurrency", ILS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].shopName").value("Future"))
                .andExpect(jsonPath("$[0].priceConverted").value("150.0000"))
                .andExpect(jsonPath("$[1].shopName").value("Tied"))
                .andExpect(jsonPath("$[1].priceConverted").value("250.0000"));
    }

    // --- the TTL divergence, asserted head-on ---

    @Test
    void observationOlderThanTheTtlLeavesTheRowUnpricedAndUnknown() throws Exception {
        Product product = seedProduct("Framework Laptop 16");
        TrackedItem tms = seedItem(product, "TMS", 3);
        seedRecord(tms, "8890", ILS, daysAgo(9));

        mvc.perform(get("/api/tracked-products").param("displayCurrency", ILS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].bestPriceConverted").doesNotExist())
                .andExpect(jsonPath("$.items[0].bestPriceConvertedCurrency").doesNotExist())
                .andExpect(jsonPath("$.items[0].bestPriceShop").doesNotExist())
                .andExpect(jsonPath("$.items[0].delta7d").doesNotExist())
                // The listing is not dropped from the denominator — "we haven't checked" is the answer.
                .andExpect(jsonPath("$.items[0].availability.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.items[0].availability.availableCount").value(0))
                .andExpect(jsonPath("$.items[0].availability.total").value(1));
    }

    @Test
    void unavailableLatestObservationIsExcludedFromBestPriceOnBothEndpoints() throws Exception {
        Product product = seedProduct("Nintendo Switch 2");
        TrackedItem ksp = seedItem(product, "KSP", 4);
        TrackedItem bug = seedItem(product, "Bug", 5);
        seedRecord(ksp, "1799", ILS, hoursAgo(4), AvailabilityStatus.UNAVAILABLE);
        seedRecord(bug, "1899", ILS, hoursAgo(3));

        mvc.perform(get("/api/tracked-products").param("displayCurrency", ILS))
                .andExpect(jsonPath("$.items[0].bestPriceConverted").value("1899.0000"))
                .andExpect(jsonPath("$.items[0].bestPriceShop").value("Bug"))
                .andExpect(jsonPath("$.items[0].availability.status").value("MIXED"))
                .andExpect(jsonPath("$.items[0].availability.availableCount").value(1));

        mvc.perform(get("/api/products/{id}/price-trend", product.getId()).param("displayCurrency", ILS))
                .andExpect(jsonPath("$.sparkline[-1:].price").value("1899.0000"));
    }

    // --- query behaviour over real data ---

    @Test
    void searchFilterAndSortNarrowTheRows_whileTheGlobalTilesStandStill() throws Exception {
        Product sony = seedProduct("Sony WH-1000XM5");
        Product keychron = seedProduct("Keychron K8 Pro");
        seedRecord(seedItem(sony, "KSP", 6), "1000", ILS, hoursAgo(2));
        seedRecord(seedItem(keychron, "Amazon", 7), "300", ILS, hoursAgo(2));

        mvc.perform(get("/api/tracked-products").param("search", "sony").param("displayCurrency", ILS))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Sony WH-1000XM5"))
                .andExpect(jsonPath("$.summaryForCurrentQuery.totalTracked").value(1))
                .andExpect(jsonPath("$.globalSummary.totalTracked").value(2))
                // Filtering must never shrink the chip row, or the filter becomes a one-way door.
                .andExpect(jsonPath("$.facets.shops.length()").value(2));

        mvc.perform(get("/api/tracked-products")
                        .param("sort", "lowestCurrentPrice")
                        .param("displayCurrency", ILS))
                .andExpect(jsonPath("$.items[0].name").value("Keychron K8 Pro"));

        mvc.perform(get("/api/tracked-products").param("shops", "amazon").param("displayCurrency", ILS))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("Keychron K8 Pro"));
    }

    @Test
    void caseVariantShopsAreOneChipThatSelectsBothListings() throws Exception {
        Product sony = seedProduct("Sony WH-1000XM5");
        Product keychron = seedProduct("Keychron K8 Pro");
        seedRecord(seedItem(sony, "Amazon", 8), "1000", ILS, hoursAgo(2));
        seedRecord(seedItem(keychron, "amazon", 9), "300", ILS, hoursAgo(2));

        mvc.perform(get("/api/tracked-products").param("displayCurrency", ILS))
                .andExpect(jsonPath("$.facets.shops.length()").value(1));

        mvc.perform(get("/api/tracked-products").param("shops", "AMAZON").param("displayCurrency", ILS))
                .andExpect(jsonPath("$.items.length()").value(2));
    }

    @Test
    void pagingIsOneBased_andTheOverflowPageIsEmptyWithTruthfulTotals() throws Exception {
        seedProduct("A");
        seedProduct("B");
        seedProduct("C");

        mvc.perform(get("/api/tracked-products").param("page", "2").param("size", "2"))
                .andExpect(jsonPath("$.page.number").value(2))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].name").value("C"));

        mvc.perform(get("/api/tracked-products").param("page", "9").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isEmpty())
                .andExpect(jsonPath("$.page.totalElements").value(3))
                .andExpect(jsonPath("$.page.totalPages").value(2));

        mvc.perform(get("/api/tracked-products").param("page", "0")).andExpect(status().isBadRequest());
    }

    // --- fixtures ---

    /**
     * Six products, each one a case where a naive two-cutoff query would diverge from the engine.
     *
     * <p>Seeded together on purpose: the lean pass runs over the whole catalogue in one query, so a
     * bug that only shows up when several shapes share a result set — a mis-partitioned ranking, a
     * lost side tag — needs them all present at once to surface.
     */
    private void seedTheAwkwardFixtures() {
        // 1. Ordinary drop, single currency.
        Product dropped = seedProduct("A Dropped");
        TrackedItem droppedKsp = seedItem(dropped, "KSP", 20);
        seedRecord(droppedKsp, "800", ILS, daysAgo(8));
        seedRecord(droppedKsp, "720", ILS, hoursAgo(2));

        // 2. Cross-currency, with the winner switching shops between the two cutoffs.
        Product crossed = seedProduct("B Crossed");
        TrackedItem crossedElectra = seedItem(crossed, "Electra", 21);
        TrackedItem crossedAmazon = seedItem(crossed, "Amazon", 22);
        seedRecord(crossedElectra, "420", ILS, daysAgo(8));
        seedRecord(crossedAmazon, "119", USD, daysAgo(8));
        seedRecord(crossedElectra, "385", ILS, hoursAgo(6));
        seedRecord(crossedAmazon, "102", USD, hoursAgo(5));

        // 3. UNAVAILABLE latest — cancels that listing's carry-forward on both sides.
        Product gone = seedProduct("C Gone");
        TrackedItem goneKsp = seedItem(gone, "KSP", 23);
        TrackedItem goneBug = seedItem(gone, "Bug", 24);
        seedRecord(goneKsp, "500", ILS, daysAgo(8));
        seedRecord(goneKsp, "450", ILS, hoursAgo(4), AvailabilityStatus.UNAVAILABLE);
        seedRecord(goneBug, "600", ILS, daysAgo(8));
        seedRecord(goneBug, "590", ILS, hoursAgo(3));

        // 4. TTL-expired — no current offer at all, so no headline and no delta.
        Product stale = seedProduct("D Stale");
        seedRecord(seedItem(stale, "TMS", 25), "8890", ILS, daysAgo(9));

        // 5. Tied timestamps — the id tiebreak has to pick the same record on both paths.
        Product tied = seedProduct("E Tied");
        TrackedItem tiedShop = seedItem(tied, "Ivory", 26);
        Instant tie = hoursAgo(2);
        seedRecord(tiedShop, "300", ILS, daysAgo(8));
        seedRecord(tiedShop, "260", ILS, tie);
        seedRecord(tiedShop, "250", ILS, tie);

        // 6. No baseline — under a week of history, so the delta is null while the price is real.
        Product fresh = seedProduct("F Fresh");
        seedRecord(seedItem(fresh, "KSP", 27), "199", ILS, daysAgo(2));
    }

    /** The awkward fixtures plus the two the listings panel adds (#157). */
    private void seedThePanelFixtures() {
        seedTheAwkwardFixtures();

        // 7. Future-dated latest record alongside an earlier current one; the earlier one counts.
        Product future = seedProduct("G Future");
        TrackedItem futureShop = seedItem(future, "KSP", 28);
        seedRecord(futureShop, "150", ILS, hoursAgo(5));
        seedRecord(futureShop, "100", ILS, FIXED_NOW.plus(1, ChronoUnit.DAYS));

        // 8. An unconvertible currency next to a convertible one: the panel keeps the GBP listing
        // (original price, no converted amount, sorted after the priced ones); the row ignores it.
        Product mixed = seedProduct("H Unconvertible");
        TrackedItem mixedIls = seedItem(mixed, "Ivory", 29);
        TrackedItem mixedGbp = seedItem(mixed, "Argos", 30);
        seedRecord(mixedIls, "700", ILS, hoursAgo(1));
        seedRecord(mixedGbp, "50", "GBP", hoursAgo(1));
    }

    private Product seedProduct(String name) {
        return productRepository.save(Product.builder().name(name).build());
    }

    private TrackedItem seedItem(Product product, String shop, int itemNo) {
        return seedItem(product, shop, itemNo, null);
    }

    private TrackedItem seedItem(Product product, String shop, int itemNo, ShopNameSource shopNameSource) {
        return trackedItemRepository.save(TrackedItem.builder()
                .url("https://shop.seed.invalid/item/" + itemNo)
                .shopName(shop)
                .shopNameSource(shopNameSource)
                .product(product)
                .build());
    }

    private void seedRecord(TrackedItem item, String price, String currency, Instant at) {
        seedRecord(item, price, currency, at, AvailabilityStatus.AVAILABLE);
    }

    private void seedRecord(
            TrackedItem item, String price, String currency, Instant at, AvailabilityStatus availability) {
        priceRecordRepository.save(PriceRecord.builder()
                .price(new BigDecimal(price))
                .currency(currency)
                .availability(availability)
                .extractionSource(ExtractionSource.STRUCTURED)
                .trackedItem(item)
                .observedAt(at)
                .build());
    }

    private void seedRate(String quote, LocalDate asOf, String rate) {
        exchangeRateRepository.save(ExchangeRate.builder()
                .quote(quote)
                .asOf(asOf)
                .rate(new BigDecimal(rate))
                .build());
    }

    private static Instant daysAgo(int days) {
        return FIXED_NOW.minus(days, ChronoUnit.DAYS);
    }

    private static Instant hoursAgo(int hours) {
        return FIXED_NOW.minus(hours, ChronoUnit.HOURS);
    }

    /** JsonPath throws on a missing leaf under the default configuration; absent means null here. */
    private static Object readOrNull(String json, String path) {
        try {
            return JsonPath.read(json, path);
        } catch (RuntimeException missing) {
            return null;
        }
    }

    private static Object unwrapSingleton(Object filtered) {
        if (filtered instanceof List<?> list) {
            return list.isEmpty() ? null : list.get(0);
        }
        return filtered;
    }

    /** Normalizes across the two wire shapes — money is a string, delta7d a number — before comparing. */
    private static BigDecimal asDecimal(Object value) {
        return value == null ? null : new BigDecimal(value.toString()).stripTrailingZeros();
    }
}
