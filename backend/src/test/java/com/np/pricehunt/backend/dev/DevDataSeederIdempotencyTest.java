package com.np.pricehunt.backend.dev;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.config.UrlValidationProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExchangeRate;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.repository.ExchangeRateRepository;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.validator.HostResolver;
import com.np.pricehunt.backend.validator.UrlValidator;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs the real, Spring-proxied seeder against H2.
 *
 * <p>Two wiring details matter. The bean is <b>autowired</b>, not constructed by hand, or {@code
 * @Transactional} on {@code run()} would not apply. And each test runs with {@code NOT_SUPPORTED}, so
 * the surrounding {@code @DataJpaTest} transaction doesn't mask what actually commits — otherwise
 * "re-running is idempotent" would be tested against data that never hit the database.
 */
@DataJpaTest
@ActiveProfiles({"test", "seed"})
@Import({DevDataSeeder.class, DevDataSeederIdempotencyTest.FixedClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DevDataSeederIdempotencyTest {

    private static final Instant NOW = Instant.parse("2026-03-20T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.of(2026, 3, 20);

    @TestConfiguration
    static class FixedClockConfig {
        // @DataJpaTest does not load ClockConfig, and a pinned clock keeps the back-dated fixtures
        // landing on exactly the boundaries they are meant to exercise.
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private DevDataSeeder seeder;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private TrackedItemRepository trackedItemRepository;

    @Autowired
    private PriceRecordRepository priceRecordRepository;

    @Autowired
    private ExchangeRateRepository exchangeRateRepository;

    @BeforeEach
    @AfterEach
    void clean() {
        productRepository.deleteAll();
        exchangeRateRepository.deleteAll();
    }

    @Test
    void seedsProductsListingsAndHistory() {
        seeder.run();

        List<Product> products = productRepository.findByDescriptionStartingWith(DevDataSeeder.SEED_MARKER);
        assertThat(products).hasSize(10);
        assertThat(trackedItemRepository.findAll()).isNotEmpty();
        assertThat(priceRecordRepository.findAll()).isNotEmpty();
    }

    @Test
    void runningTwiceLeavesTheSameData() {
        seeder.run();
        long productsAfterFirst = productRepository.count();
        long itemsAfterFirst = trackedItemRepository.count();
        long recordsAfterFirst = priceRecordRepository.count();
        long ratesAfterFirst = exchangeRateRepository.count();

        seeder.run();

        assertThat(productRepository.count()).isEqualTo(productsAfterFirst);
        assertThat(trackedItemRepository.count()).isEqualTo(itemsAfterFirst);
        assertThat(priceRecordRepository.count()).isEqualTo(recordsAfterFirst);
        // Rates are insert-if-absent, never deleted, so the second run must add nothing.
        assertThat(exchangeRateRepository.count()).isEqualTo(ratesAfterFirst);
    }

    @Test
    void leavesProductsItDidNotWriteAlone() {
        Product real = productRepository.save(Product.builder()
                .name("Real product")
                .description("A genuine listing")
                .build());
        Product mentionsMarker = productRepository.save(Product.builder()
                .name("Mentions the marker")
                // Contains the marker, but not as a prefix: prefix matching must not sweep it up.
                .description("Imported from " + DevDataSeeder.SEED_MARKER + " docs")
                .build());

        seeder.run();
        seeder.run();

        assertThat(productRepository.findById(real.getId())).isPresent();
        assertThat(productRepository.findById(mentionsMarker.getId())).isPresent();
    }

    @Test
    void preservesRealExchangeRatesAndOnlyFillsGaps() {
        ExchangeRate real = exchangeRateRepository.save(ExchangeRate.builder()
                .quote("USD")
                .asOf(TODAY.minusDays(5))
                .rate(new BigDecimal("9.99999999"))
                .build());

        seeder.run();

        ExchangeRate reloaded = exchangeRateRepository.findById(real.getId()).orElseThrow();
        assertThat(reloaded.getRate()).isEqualByComparingTo("9.99999999");
    }

    @Test
    void backDatesHistoryAndKeepsLastCheckedInSyncWithTheNewestRecord() {
        seeder.run();

        TrackedItem withHistory = trackedItemRepository.findAll().stream()
                .filter(item -> !priceRecordRepository
                        .findByTrackedItemOrderByTimestampDesc(item)
                        .isEmpty())
                .findFirst()
                .orElseThrow();
        List<PriceRecord> history = priceRecordRepository.findByTrackedItemOrderByTimestampDesc(withHistory);

        assertThat(history)
                .allSatisfy(record -> assertThat(record.getTimestamp()).isBefore(NOW));
        assertThat(history.get(history.size() - 1).getTimestamp()).isBefore(NOW.minus(Duration.ofDays(1)));
        assertThat(withHistory.getLastChecked()).isEqualTo(history.get(0).getTimestamp());
    }

    @Test
    void includesTheEdgeStatesTheTrendRulesDependOn() {
        seeder.run();

        List<PriceRecord> allRecords = priceRecordRepository.findAll();
        assertThat(allRecords)
                .extracting(PriceRecord::getAvailability)
                .contains(AvailabilityStatus.AVAILABLE, AvailabilityStatus.UNAVAILABLE, AvailabilityStatus.UNKNOWN);
        assertThat(allRecords).extracting(PriceRecord::getCurrency).contains("ILS", "USD");

        // A sample stamped exactly seven days back — the inclusive baseline boundary.
        assertThat(allRecords)
                .anySatisfy(record -> assertThat(record.getTimestamp()).isEqualTo(NOW.minus(Duration.ofDays(7))));

        // A product with no listings, and a listing that was never checked. Map to plain values before
        // asserting: AssertJ renders the entity on failure, and @Data's toString would hydrate the lazy
        // collections outside a session.
        List<Integer> listingCounts = productRepository.findAll().stream()
                .map(product -> trackedItemRepository.findByProduct(product).size())
                .toList();
        assertThat(listingCounts).contains(0);

        assertThat(trackedItemRepository.findAll().stream()
                        .map(TrackedItem::getLastChecked)
                        .toList())
                .containsNull();
    }

    @Test
    void seededFxNeverReachesTheLastTwoDaysSoTheStartupRefreshStillRuns() {
        seeder.run();

        assertThat(exchangeRateRepository.findAll()).isNotEmpty().allSatisfy(rate -> assertThat(rate.getAsOf())
                .isBeforeOrEqualTo(TODAY.minusDays(2)));
    }

    @Test
    void everySeededUrlIsBlocklistedSoTheSchedulerSkipsIt() {
        seeder.run();
        UrlValidator validator = new UrlValidator(
                new UrlValidationProperties(
                        true,
                        List.of("(^|\\.)amazon\\.[a-z]{2,3}(\\.[a-z]{2})?$", "(^|\\.)seed\\.invalid$"),
                        Duration.ofSeconds(2),
                        8,
                        16),
                mockResolver());

        assertThat(trackedItemRepository.findAll()).isNotEmpty().allSatisfy(item -> assertThat(
                        validator.isUnsupportedHost(item.getUrl()))
                .as("seeded URL %s must be blocklisted", item.getUrl())
                .isTrue());
    }

    private static HostResolver mockResolver() {
        return org.mockito.Mockito.mock(HostResolver.class);
    }
}
