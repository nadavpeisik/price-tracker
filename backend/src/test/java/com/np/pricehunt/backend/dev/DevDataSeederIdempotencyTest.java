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
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
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
@EnableConfigurationProperties(UrlValidationProperties.class)
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

    /** The real, bound validation config — the test below starts from it rather than a hand-made copy. */
    @Autowired
    private UrlValidationProperties urlValidationProperties;

    @BeforeEach
    @AfterEach
    void clean() {
        productRepository.deleteAll();
        exchangeRateRepository.deleteAll();
    }

    @Test
    void aRealProductNamedLikeAFixtureSurvives_andItsFixtureIsSkipped() {
        // seed-clean, then the user tracks the real thing, then seed again (V13 unique names).
        Product real = productRepository.saveAndFlush(
                Product.builder().name("sony wh-1000xm5").description("mine").build());

        seeder.run();

        List<Product> sameName = productRepository.findAll().stream()
                .filter(p -> p.getName().equalsIgnoreCase("Sony WH-1000XM5"))
                .toList();
        assertThat(sameName).extracting(Product::getId).containsExactly(real.getId());
        assertThat(sameName.get(0).getDescription()).isEqualTo("mine");
        // Every other fixture still lands.
        assertThat(productRepository.count()).isEqualTo(10 + DevDataSeeder.FILLER_COUNT);
    }

    @Test
    void seedsProductsListingsAndHistory() {
        seeder.run();

        List<Product> products = productRepository.findByDescriptionStartingWith(DevDataSeeder.SEED_MARKER);
        assertThat(products).hasSize(10 + DevDataSeeder.FILLER_COUNT);
        assertThat(trackedItemRepository.findAll()).isNotEmpty();
        assertThat(priceRecordRepository.findAll()).isNotEmpty();
    }

    @Test
    void seedsMoreThanOneDashboardPage_soPaginationIsReachableAgainstRealData() {
        seeder.run();

        // The dashboard's default page size is 20 (#157): the clamp of a bookmarked overflow page and
        // the pagination controls only render when there is a second page.
        assertThat(productRepository.count()).isGreaterThan(20);
    }

    @Test
    void seedsOneCaseVariantShopSpelling_withTheCanonicalSpellingStillTheMajority() {
        seeder.run();

        List<String> shopNames = trackedItemRepository.findAll().stream()
                .map(TrackedItem::getShopName)
                .toList();

        // Exercises the dashboard's shop fold and the client-side canonicalization of ?shop=ksp (#157),
        // while the facet label stays "KSP" because the majority rule picks the more frequent spelling.
        assertThat(shopNames).contains("ksp");
        assertThat(shopNames.stream().filter("KSP"::equals).count())
                .isGreaterThan(shopNames.stream().filter("ksp"::equals).count());
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
        // Must be a WEEKDAY the seeder would otherwise write: TODAY is Friday 2026-03-20, so −5 lands on
        // a Sunday, which the seeder skips — the collision would never occur and this would pass even
        // with the duplicate check removed. −4 is Monday 2026-03-16, squarely inside the seeded set.
        LocalDate collidingWeekday = TODAY.minusDays(4);
        assertThat(collidingWeekday.getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);

        ExchangeRate real = exchangeRateRepository.save(ExchangeRate.builder()
                .quote("USD")
                .asOf(collidingWeekday)
                .rate(new BigDecimal("9.99999999"))
                .build());

        seeder.run();

        // The real row survives untouched...
        ExchangeRate reloaded = exchangeRateRepository.findById(real.getId()).orElseThrow();
        assertThat(reloaded.getRate()).isEqualByComparingTo("9.99999999");
        // ...and the seeder added no second row for that (quote, asOf).
        assertThat(exchangeRateRepository.findAll().stream()
                        .filter(rate -> "USD".equals(rate.getQuote()) && collidingWeekday.equals(rate.getAsOf()))
                        .toList())
                .hasSize(1);
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

        assertThat(history).allSatisfy(observation -> assertThat(observation.getTimestamp())
                .isBefore(NOW));
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
        assertThat(allRecords).anySatisfy(observation -> assertThat(observation.getTimestamp())
                .isEqualTo(NOW.minus(Duration.ofDays(7))));

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
    void everySeededUrlIsSkippedByTheSchedulerEvenWithTheBlocklistDisabled() {
        seeder.run();
        // Bind the REAL config, then switch the blocklist OFF — the state a run started with
        // --price.validation.unsupported-sites-enabled=false is in. The seeded .invalid hosts must
        // still be skipped: while that guarantee lived in the configurable blocklist, such a run spent
        // a DNS lookup and a FAILED job-run item on every seeded listing, once per scheduler pass.
        UrlValidationProperties blocklistOff = new UrlValidationProperties(
                false,
                urlValidationProperties.unsupportedHostPatterns(),
                urlValidationProperties.dnsResolveTimeout(),
                urlValidationProperties.dnsResolverPoolSize(),
                urlValidationProperties.dnsResolverQueueCapacity());
        UrlValidator validator = new UrlValidator(blocklistOff, mockResolver());

        assertThat(trackedItemRepository.findAll()).isNotEmpty().allSatisfy(item -> assertThat(
                        validator.isNeverScrapable(item.getUrl()))
                .as("seeded URL %s must never be scraped", item.getUrl())
                .isTrue());
    }

    private static HostResolver mockResolver() {
        return org.mockito.Mockito.mock(HostResolver.class);
    }
}
