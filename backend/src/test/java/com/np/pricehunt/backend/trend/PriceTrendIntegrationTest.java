package com.np.pricehunt.backend.trend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExchangeRate;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
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
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * One end-to-end pass through the real wiring: repositories, historical FX flooring, the calculator
 * and JSON mapping — the seams the unit tests deliberately stub out.
 *
 * <p>Everything the application would otherwise start is switched off explicitly: Docker Compose, the
 * price scheduler, Ollama model pulls, and all three cron jobs ({@code price.scheduler.enabled} gates
 * only one of them, and a test straddling a cron instant could mutate the database underneath it).
 * The FX provider is mocked, so the test passes with no network and no Ollama running.
 */
@SpringBootTest
@ActiveProfiles("test")
@org.springframework.test.context.TestPropertySource(
        properties = {
            "spring.docker.compose.enabled=false",
            "price.scheduler.enabled=false",
            "spring.ai.ollama.init.pull-model-strategy=never",
            "pricehunt.currency.fx.refresh-cron=-",
            "scrape.audit.purge-cron=-",
        })
class PriceTrendIntegrationTest {

    private static final String ILS = "ILS";
    private static final String USD = "USD";
    private static final Instant FIXED_NOW = Instant.parse("2026-03-20T12:00:00Z");

    /**
     * Production reads "now" from the application {@link Clock}; the fixtures must read it from the
     * same place, or a run that crosses UTC midnight would seed one day and assert against another.
     */
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
    private Clock clock;

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
    private Instant now;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
        now = clock.instant();
        today = LocalDate.ofInstant(now, ZoneOffset.UTC);

        productRepository.deleteAll();
        exchangeRateRepository.deleteAll();

        // @MockitoBean can't be stubbed before ApplicationReadyEvent, so the startup refresh hit an
        // unstubbed mock and persisted nothing (refresh() swallows that). Install the deterministic
        // snapshot now, explicitly.
        when(rateProvider.fetchLatest())
                .thenReturn(new RateSnapshot(today, Map.of(USD, new BigDecimal("1.10"), ILS, new BigDecimal("4.00"))));
        rateService.refresh();
    }

    @Test
    void crossCurrencyProduct_seriesIsFxNormalizedPerDay() throws Exception {
        // Historical rates with a deliberate hole: the most recent historical date carries USD only,
        // so ILS must fall back to its own earlier rate rather than becoming unconvertible.
        seedRate(USD, today.minusDays(6), "1.10");
        seedRate(ILS, today.minusDays(6), "4.00");
        seedRate(USD, today.minusDays(3), "1.10");

        Product product = seedProduct("Keychron K8 Pro");
        TrackedItem electra = seedItem(product, "אלקטרה", "electra", 1);
        TrackedItem amazon = seedItem(product, "Amazon", "amazon-seed", 2);
        seedRecord(electra, "385", ILS, hoursAgo(6));
        seedRecord(amazon, "102", USD, hoursAgo(5));
        seedRecord(electra, "420", ILS, daysAgo(4));
        seedRecord(amazon, "119", USD, daysAgo(4));

        String todayIso = today.atStartOfDay(ZoneOffset.UTC).toInstant().toString();

        // $102 → ₪370.9091 beats ₪385, and the cheaper listing is named in bestOffer.
        mvc.perform(get("/api/products/{id}/price-trend", product.getId())
                        .param("days", "7")
                        .param("displayCurrency", ILS)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayCurrency").value(ILS))
                .andExpect(jsonPath("$.sparkline[-1:].t").value(todayIso))
                .andExpect(jsonPath("$.sparkline[-1:].price").value("370.9091"))
                .andExpect(jsonPath("$.sparkline[-1:].bestOffer.shopName").value("Amazon"))
                .andExpect(jsonPath("$.sparkline[-1:].bestOffer.trackedItemId")
                        .value(amazon.getId().intValue()));

        // Four days back the shops are the other way round: ₪420 beats $119 (≈₪432.73 at that day's
        // floored rate), so the winner legitimately switches between shops across the window — and the
        // old day is valued at the historical rate, not today's.
        mvc.perform(get("/api/products/{id}/price-trend", product.getId())
                        .param("days", "7")
                        .param("displayCurrency", ILS))
                .andExpect(jsonPath("$.sparkline[0].price").value("420.0000"))
                .andExpect(jsonPath("$.sparkline[0].bestOffer.trackedItemId")
                        .value(electra.getId().intValue()));
    }

    @Test
    void noCurrentPrice_meansNoTodayPoint() throws Exception {
        Product product = seedProduct("Framework Laptop 16");
        TrackedItem tms = seedItem(product, "TMS", "tms", 3);
        seedRecord(tms, "8890", ILS, daysAgo(9));

        // The single record is 9 days old, so carry-forward (7d TTL) expires 2 days back: the series
        // must end on today−3 rather than today. Assert the exact instant — a `not(today)` matcher on a
        // list path would also pass on an empty sparkline or a wrong-but-not-today last point.
        String expectedLastPoint =
                today.minusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant().toString();

        mvc.perform(get("/api/products/{id}/price-trend", product.getId())
                        .param("days", "14")
                        .param("displayCurrency", ILS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sparkline[-1:].t").value(expectedLastPoint))
                .andExpect(jsonPath("$.delta7d").doesNotExist());
    }

    @Test
    void unavailableListingIsExcludedFromTheSeriesBestOffer() throws Exception {
        Product product = seedProduct("Nintendo Switch 2");
        TrackedItem ksp = seedItem(product, "KSP", "ksp", 4);
        TrackedItem bug = seedItem(product, "Bug", "bug", 5);
        seedRecord(ksp, "1799", ILS, hoursAgo(4), AvailabilityStatus.UNAVAILABLE);
        seedRecord(bug, "1899", ILS, hoursAgo(3), AvailabilityStatus.AVAILABLE);

        mvc.perform(get("/api/products/{id}/price-trend", product.getId())
                        .param("days", "3")
                        .param("displayCurrency", ILS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sparkline[-1:].price").value("1899.0000"))
                .andExpect(jsonPath("$.sparkline[-1:].bestOffer.shopName").value("Bug"));
    }

    @Test
    void sameCurrencyProduct_reportsDeltaAndLeavesConversionMetadataEmpty() throws Exception {
        Product product = seedProduct("Samsung 990 Pro");
        TrackedItem ksp = seedItem(product, "KSP", "ksp", 6);
        seedRecord(ksp, "800", ILS, daysAgo(8));
        seedRecord(ksp, "720", ILS, hoursAgo(2));

        mvc.perform(get("/api/products/{id}/price-trend", product.getId())
                        .param("days", "10")
                        .param("displayCurrency", ILS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.delta7d").value(-10.00))
                // Same-currency conversions consult no rate at all.
                .andExpect(jsonPath("$.conversionStale").value(false))
                .andExpect(jsonPath("$.conversionAsOf").doesNotExist());
    }

    @Test
    void staleRateOnAnOlderPointDoesNotFlagTheResponseOrDropTheDelta() throws Exception {
        // Fresh rates from a week back; one ancient date serves only the far-past point, more than a
        // week before it, so that point's conversion is stale while today's is not.
        // (d starts at 1: today's row already exists, written by the refresh in setUp.)
        seedRate(USD, today.minusDays(50), "1.10");
        seedRate(ILS, today.minusDays(50), "4.00");
        for (int d = 9; d >= 1; d--) {
            seedRate(USD, today.minusDays(d), "1.10");
            seedRate(ILS, today.minusDays(d), "4.00");
        }

        Product product = seedProduct("Sony WH-1000XM5");
        TrackedItem ivory = seedItem(product, "Ivory", "ivory", 7);
        seedRecord(ivory, "150", USD, daysAgo(35));
        seedRecord(ivory, "120", USD, daysAgo(8));
        seedRecord(ivory, "100", USD, hoursAgo(2));

        mvc.perform(get("/api/products/{id}/price-trend", product.getId())
                        .param("days", "40")
                        .param("displayCurrency", ILS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conversionStale").value(false))
                .andExpect(jsonPath("$.delta7d").value(-16.67));
    }

    @Test
    void unknownProductIs404AndBadRequestsAreRejected() throws Exception {
        mvc.perform(get("/api/products/{id}/price-trend", 999_999L)).andExpect(status().isNotFound());

        Product product = seedProduct("Anything");
        mvc.perform(get("/api/products/{id}/price-trend", product.getId()).param("days", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/{id}/price-trend", product.getId()).param("displayCurrency", "ZZZZ"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/products/{id}/price-trend", product.getId()).param("days", "99999"))
                .andExpect(status().isOk());
    }

    @Test
    void productWithoutListingsReturnsAnEmptySeries() throws Exception {
        Product product = seedProduct("Bambu Lab A1 mini");

        mvc.perform(get("/api/products/{id}/price-trend", product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sparkline").isEmpty())
                .andExpect(jsonPath("$.delta7d").doesNotExist());

        assertThat(trackedItemRepository.findByProduct(product)).isEmpty();
    }

    // --- fixtures ---

    private Product seedProduct(String name) {
        return productRepository.save(Product.builder().name(name).build());
    }

    private TrackedItem seedItem(Product product, String shop, String host, int itemNo) {
        return trackedItemRepository.save(TrackedItem.builder()
                .url("https://" + host + ".seed.invalid/item/" + itemNo)
                .shopName(shop)
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

    private Instant daysAgo(int days) {
        return now.minus(days, ChronoUnit.DAYS);
    }

    private Instant hoursAgo(int hours) {
        return now.minus(hours, ChronoUnit.HOURS);
    }
}
