package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// Real Postgres for the V12 created_at backfill (issue #225). The other Testcontainers tests boot
// with every migration applied, which proves V12 runs on an empty database and that the entities
// validate against it — but not what V12 does to rows that already exist. This test stops Flyway at
// V11, inserts the pre-V11 shapes (a listing with history, one without, a product with no listings),
// then applies V12 and checks each row got the timestamp the migration promises.
@JdbcTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
// Not inside the test transaction @JdbcTest opens by default: the inserted rows would hold locks on
// product/tracked_item while Flyway's ALTER TABLE, on its own connection, waits for them — forever.
// The container is per-class, so nothing needs rolling back.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CreatedAtMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.target", () -> "11");
        registry.add("spring.docker.compose.enabled", () -> false);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Test
    void backfillsFromEarliestPriceRecordAndFallsBackToNow() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant oldest = Instant.parse("2025-01-10T10:00:00Z");
        Instant later = Instant.parse("2025-03-01T10:00:00Z");

        long withHistory = insertProduct("Tracked, with history");
        long observedItem = insertItem(withHistory, "https://a.example/1");
        insertPrice(observedItem, later);
        insertPrice(observedItem, oldest);
        long neverObservedItem = insertItem(withHistory, "https://a.example/2");
        long bareProduct = insertProduct("No listings at all");

        Flyway.configure()
                .configuration(flyway.getConfiguration())
                .target("12")
                .load()
                .migrate();

        assertThat(createdAt("tracked_item", observedItem)).isEqualTo(oldest);
        assertThat(createdAt("tracked_item", neverObservedItem)).isAfterOrEqualTo(before);
        // The product inherits its earliest listing — the observed one, not the now()-stamped one.
        assertThat(createdAt("product", withHistory)).isEqualTo(oldest);
        assertThat(createdAt("product", bareProduct)).isAfterOrEqualTo(before);

        List<Boolean> nullable = jdbc.queryForList(
                "SELECT is_nullable = 'YES' FROM information_schema.columns"
                        + " WHERE table_name IN ('product', 'tracked_item') AND column_name = 'created_at'",
                Boolean.class);
        assertThat(nullable).containsExactly(false, false);
    }

    private long insertProduct(String name) {
        return jdbc.queryForObject("INSERT INTO product (name) VALUES (?) RETURNING id", Long.class, name);
    }

    private long insertItem(long productId, String url) {
        return jdbc.queryForObject(
                "INSERT INTO tracked_item (product_id, url) VALUES (?, ?) RETURNING id", Long.class, productId, url);
    }

    private void insertPrice(long itemId, Instant at) {
        jdbc.update(
                "INSERT INTO price_record"
                        + " (price, currency, availability_status, extraction_source, tracked_item_id, \"timestamp\")"
                        + " VALUES (1.0000, 'USD', 'AVAILABLE', 'STRUCTURED', ?, ?)",
                itemId,
                OffsetDateTime.ofInstant(at, ZoneOffset.UTC));
    }

    private Instant createdAt(String table, long id) {
        return jdbc.queryForObject("SELECT created_at FROM " + table + " WHERE id = ?", OffsetDateTime.class, id)
                .toInstant();
    }
}
