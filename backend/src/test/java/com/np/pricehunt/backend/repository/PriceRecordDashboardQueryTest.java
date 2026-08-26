package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.repository.projection.CutoffObservationRow;
import com.np.pricehunt.backend.repository.projection.CutoffSide;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * The dashboard's two-cutoff query against real Postgres (issue #146).
 *
 * <p>Testcontainers rather than H2 for two reasons: Flyway runs V1–V10 with {@code
 * ddl-auto=validate}, so this doubles as the V10 migration/entity gate, and the query's window
 * functions, quoted-alias projection binding and {@code timestamptz} comparisons are exactly the
 * semantics H2 approximates rather than reproduces.
 *
 * <p>The window bounds are passed explicitly here — the production values come from {@code
 * DashboardSnapshotService}; what this pins is what the SQL does with them.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
class PriceRecordDashboardQueryTest {

    /** Matches the default {@code price.trend.carry-forward-days}; ≥ 7 so the two windows overlap. */
    private static final int TTL_DAYS = 7;

    private static final Instant AS_OF = Instant.parse("2026-03-20T12:00:00Z");
    private static final Instant BASELINE_CUTOFF = AS_OF.minus(7, ChronoUnit.DAYS);
    private static final Instant CURRENT_FLOOR = AS_OF.minus(TTL_DAYS, ChronoUnit.DAYS);
    private static final Instant BASELINE_FLOOR = BASELINE_CUTOFF.minus(TTL_DAYS, ChronoUnit.DAYS);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.docker.compose.enabled", () -> false);
    }

    @Autowired
    private PriceRecordRepository repository;

    @Autowired
    private TestEntityManager em;

    private List<CutoffObservationRow> query() {
        return repository.findCutoffObservations(CURRENT_FLOOR, AS_OF, BASELINE_FLOOR, BASELINE_CUTOFF);
    }

    @Test
    void listingWithHistoryOnBothSides_returnsExactlyOneRowPerSide() {
        // THE partition pin. Ranking after the union without PARTITION BY ... , side would rank the
        // baseline row second behind the newer current row and drop it, nulling every delta.
        TrackedItem item = seedItem();
        seedRecord(item, "100", daysBefore(8));
        seedRecord(item, "90", daysBefore(1));

        assertThat(query())
                .extracting(CutoffObservationRow::getSide, PriceRecordDashboardQueryTest::plainPrice)
                .containsExactlyInAnyOrder(tuple(CutoffSide.CURRENT, "90"), tuple(CutoffSide.BASELINE, "100"));
    }

    @Test
    void eachSideTakesItsOwnLatestRecord_notTheGlobalLatest() {
        TrackedItem item = seedItem();
        seedRecord(item, "300", daysBefore(10)); // older than the baseline cutoff — loses on both sides
        seedRecord(item, "200", daysBefore(8)); // newest at baselineCutoff
        seedRecord(item, "120", daysBefore(3));
        seedRecord(item, "110", daysBefore(1)); // newest at asOf

        assertThat(query())
                .extracting(CutoffObservationRow::getSide, PriceRecordDashboardQueryTest::plainPrice)
                .containsExactlyInAnyOrder(tuple(CutoffSide.CURRENT, "110"), tuple(CutoffSide.BASELINE, "200"));
    }

    @Test
    void tiedTimestamps_breakByHighestId() {
        TrackedItem item = seedItem();
        Instant tie = daysBefore(2);
        PriceRecord first = seedRecord(item, "500", tie);
        PriceRecord second = seedRecord(item, "400", tie);
        assertThat(second.getId()).isGreaterThan(first.getId());

        assertThat(query()).singleElement().satisfies(row -> {
            assertThat(row.getSide()).isEqualTo(CutoffSide.CURRENT);
            assertThat(row.getRecordId()).isEqualTo(second.getId());
        });
    }

    @Test
    void oneRecordInsideBothWindows_isReturnedOncePerSide() {
        // With a 7-day TTL the CURRENT window opens exactly at the BASELINE cutoff, so a record
        // stamped there is legitimately the latest for both evaluation instants. Both sides must see
        // it: the current side drives availability and the headline price, the baseline the delta.
        TrackedItem item = seedItem();
        PriceRecord shared = seedRecord(item, "250", BASELINE_CUTOFF);

        assertThat(query())
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.getRecordId()).isEqualTo(shared.getId()))
                .extracting(CutoffObservationRow::getSide)
                .containsExactlyInAnyOrder(CutoffSide.CURRENT, CutoffSide.BASELINE);
    }

    @Test
    void windowBoundsAreInclusive_onBothEnds() {
        TrackedItem atAsOf = seedItem();
        seedRecord(atAsOf, "10", AS_OF);
        TrackedItem atCurrentFloor = seedItem();
        seedRecord(atCurrentFloor, "20", CURRENT_FLOOR);
        TrackedItem atBaselineFloor = seedItem();
        seedRecord(atBaselineFloor, "30", BASELINE_FLOOR);

        assertThat(query())
                .extracting(CutoffObservationRow::getTrackedItemId)
                .contains(atAsOf.getId(), atCurrentFloor.getId(), atBaselineFloor.getId());
    }

    @Test
    void recordsOutsideBothWindowsAreAbsent() {
        TrackedItem future = seedItem();
        seedRecord(future, "10", AS_OF.plusSeconds(1)); // clock skew / manual insert
        TrackedItem ancient = seedItem();
        seedRecord(ancient, "20", BASELINE_FLOOR.minusSeconds(1));

        assertThat(query()).isEmpty();
    }

    @Test
    void listingWithNoRecordsIsAbsentEntirely() {
        seedItem();

        assertThat(query()).isEmpty();
    }

    @Test
    void projectionCarriesEveryFieldTheCalculatorNeeds() {
        TrackedItem item = seedItem();
        Instant observedAt = daysBefore(1);
        PriceRecord seeded = seedRecord(item, "42.5000", observedAt, "USD", AvailabilityStatus.UNKNOWN);

        assertThat(query()).singleElement().satisfies(row -> {
            assertThat(row.getTrackedItemId()).isEqualTo(item.getId());
            assertThat(row.getRecordId()).isEqualTo(seeded.getId());
            assertThat(row.getPrice()).isEqualByComparingTo("42.5");
            assertThat(row.getCurrency()).isEqualTo("USD");
            assertThat(row.getAvailability()).isEqualTo(AvailabilityStatus.UNKNOWN);
            assertThat(row.getObservedAt()).isEqualTo(observedAt);
            assertThat(row.getSide()).isEqualTo(CutoffSide.CURRENT);
        });
    }

    @Test
    void rankingIsPerListing_notGlobal() {
        TrackedItem cheap = seedItem();
        TrackedItem pricey = seedItem();
        seedRecord(cheap, "10", daysBefore(3));
        seedRecord(pricey, "999", daysBefore(2));

        assertThat(query())
                .extracting(CutoffObservationRow::getTrackedItemId)
                .containsExactlyInAnyOrder(cheap.getId(), pricey.getId());
    }

    // --- fixtures ---

    /** {@code numeric(19,4)} comes back as 100.0000; assert the value, not the scale. */
    private static String plainPrice(CutoffObservationRow row) {
        return row.getPrice().stripTrailingZeros().toPlainString();
    }

    private static Instant daysBefore(int days) {
        return AS_OF.minus(days, ChronoUnit.DAYS);
    }

    private TrackedItem seedItem() {
        // Each item gets its own product, and product names are unique (V13), so key both on the same
        // nonce.
        long nonce = System.nanoTime();
        Product product = em.persist(Product.builder().name("Product " + nonce).build());
        return em.persist(TrackedItem.builder()
                .url("https://shop.invalid/p/" + nonce)
                .shopName("Shop")
                .product(product)
                .build());
    }

    private PriceRecord seedRecord(TrackedItem item, String price, Instant at) {
        return seedRecord(item, price, at, "ILS", AvailabilityStatus.AVAILABLE);
    }

    private PriceRecord seedRecord(
            TrackedItem item, String price, Instant at, String currency, AvailabilityStatus availability) {
        PriceRecord seeded = em.persist(PriceRecord.builder()
                .price(new BigDecimal(price))
                .currency(currency)
                .availability(availability)
                .extractionSource(ExtractionSource.STRUCTURED)
                .trackedItem(item)
                .timestamp(at)
                .build());
        em.flush();
        return seeded;
    }
}
