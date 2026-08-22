package com.np.pricehunt.backend.dev;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
 * Runs the real, Spring-proxied seeder under {@code seed-clean} against H2 (issue #212).
 *
 * <p>Same two wiring rules as {@link DevDataSeederIdempotencyTest}: the bean is autowired so {@code
 * @Transactional} on {@code run()} applies, and {@code NOT_SUPPORTED} lets writes really commit.
 * Fixtures are hand-built rather than seeded, because under this profile the seeder writes nothing.
 */
@DataJpaTest
@ActiveProfiles({"test", "seed-clean"})
@Import({DevDataSeeder.class, DevDataSeederCleanupTest.FixedClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class DevDataSeederCleanupTest {

    private static final Instant NOW = Instant.parse("2026-03-20T12:00:00Z");

    private static final String SEEDED = "Seeded headphones";
    private static final String REAL = "Real headphones";
    private static final String MENTIONS = "Real docs entry";

    /** Contains the marker but does not start with it — the prefix-not-contains guard. */
    private static final String MENTIONS_MARKER = "Imported from " + DevDataSeeder.SEED_MARKER + " docs";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        Clock clock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    private DevDataSeeder seeder;

    @Autowired
    private ProductRepository productRepository;

    // Children are asserted through their own repositories: NOT_SUPPORTED leaves entities detached,
    // so navigating product.getTrackedItems() would throw LazyInitializationException.
    @Autowired
    private TrackedItemRepository trackedItemRepository;

    @Autowired
    private PriceRecordRepository priceRecordRepository;

    @Autowired
    private ExchangeRateRepository exchangeRateRepository;

    private Long seededId;
    private Long realId;
    private Long mentionsMarkerId;

    @AfterEach
    void clean() {
        productRepository.deleteAll();
        exchangeRateRepository.deleteAll();
    }

    // One @BeforeEach, not two: JUnit does not order sibling @BeforeEach methods, so a separate
    // cleanup method can just as easily run after the fixtures it is meant to precede.
    @BeforeEach
    void fixtures() {
        clean();
        seededId = saveProduct(SEEDED, DevDataSeeder.SEED_MARKER + "demo row");
        realId = saveProduct(REAL, "something I actually track");
        mentionsMarkerId = saveProduct(MENTIONS, MENTIONS_MARKER);

        exchangeRateRepository.saveAll(List.of(
                rate("USD", LocalDate.of(2026, 3, 10), "1.0850"), rate("ILS", LocalDate.of(2026, 3, 10), "4.0100")));
    }

    @Test
    void removesSeededProductsWithTheirListingsAndHistory() {
        seeder.run();

        assertThat(productRepository.findById(seededId)).isEmpty();
        // Exact URLs, not substrings: every fixture name here shares a word with another.
        assertThat(trackedItemRepository.findAll())
                .extracting(TrackedItem::getUrl)
                .containsExactlyInAnyOrder(urlFor(REAL), urlFor(MENTIONS));
        assertThat(priceRecordRepository.findAll()).hasSize(2); // the two survivors' rows, not the seeded one
    }

    @Test
    void leavesRealProductsAndTheirChildrenAlone() {
        seeder.run();

        assertThat(productRepository.findById(realId)).isPresent();
        assertThat(trackedItemRepository.findAll())
                .extracting(TrackedItem::getUrl)
                .contains(urlFor(REAL));
    }

    @Test
    void leavesAProductThatOnlyMentionsTheMarkerAlone() {
        seeder.run();

        assertThat(productRepository.findById(mentionsMarkerId)).isPresent();
    }

    @Test
    void writesNothing() {
        seeder.run();

        // The early return must fire before both seedExchangeRates(...) and the fixture insert.
        assertThat(exchangeRateRepository.findAll()).hasSize(2);
        assertThat(productRepository.findByDescriptionStartingWith(DevDataSeeder.SEED_MARKER))
                .isEmpty();
    }

    @Test
    void runningTwiceChangesNothingTheSecondTime() {
        seeder.run();
        List<Long> productIds = ids(productRepository.findAll());
        long items = trackedItemRepository.count();
        long records = priceRecordRepository.count();
        long rates = exchangeRateRepository.count();

        seeder.run();

        assertThat(ids(productRepository.findAll())).isEqualTo(productIds);
        assertThat(trackedItemRepository.count()).isEqualTo(items);
        assertThat(priceRecordRepository.count()).isEqualTo(records);
        assertThat(exchangeRateRepository.count()).isEqualTo(rates);
    }

    private static List<Long> ids(List<Product> products) {
        return products.stream().map(Product::getId).sorted().toList();
    }

    /**
     * Both sides of every association are set explicitly, and the collections are supplied rather than
     * relied on: {@code @Builder} drops field initializers without {@code @Builder.Default}, so the
     * lists arrive null.
     */
    private static String urlFor(String name) {
        return "https://shop.example/" + name.toLowerCase().replace(' ', '-');
    }

    private Long saveProduct(String name, String description) {
        Product product = Product.builder()
                .name(name)
                .description(description)
                .trackedItems(new ArrayList<>())
                .build();

        TrackedItem item = TrackedItem.builder()
                .url(urlFor(name))
                .shopName("Example")
                .product(product)
                .priceHistory(new ArrayList<>())
                .build();
        product.getTrackedItems().add(item);

        PriceRecord priceRecord = PriceRecord.builder()
                .price(new BigDecimal("99.9900"))
                .currency("ILS")
                .availability(AvailabilityStatus.AVAILABLE)
                .extractionSource(ExtractionSource.STRUCTURED)
                .timestamp(NOW)
                .trackedItem(item)
                .build();
        item.getPriceHistory().add(priceRecord);

        return productRepository.save(product).getId();
    }

    private static ExchangeRate rate(String quote, LocalDate asOf, String value) {
        return ExchangeRate.builder()
                .quote(quote)
                .asOf(asOf)
                .rate(new BigDecimal(value))
                .build();
    }
}
