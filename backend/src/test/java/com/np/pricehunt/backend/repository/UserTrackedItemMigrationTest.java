package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jdbc.test.autoconfigure.JdbcTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// Real Postgres for V16 (issue #244). Hibernate's validate sees the table and its columns but not
// defaults, the unique constraint, either ON DELETE action or the partial index — and the backfill
// only does anything on a database that already has listings. So, like CreatedAtMigrationTest:
// stop Flyway at V15, insert the pre-V16 shapes, apply V16, then check every promise the migration
// makes. (Every @DataJpaTest that boots with all migrations + validate covers the entity mapping.)
@JdbcTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
// Not inside @JdbcTest's transaction: the inserted rows would hold locks Flyway's DDL, on its own
// connection, waits for forever. The container is per-class, so nothing needs rolling back.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserTrackedItemMigrationTest {

    private static final String PLACEHOLDER_ISSUER = "https://auth0-tenant-pending.invalid/";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.target", () -> "15");
        registry.add("spring.docker.compose.enabled", () -> false);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Test
    void backfillLinksEveryListingToThePlaceholderUser_thenTheTableKeepsItsPromises() {
        Instant older = Instant.parse("2025-01-10T10:00:00Z");
        Instant newer = Instant.parse("2025-03-01T10:00:00Z");
        long product = insertProduct("Headphones");
        long olderItem = insertItem(product, "https://a.example/1", older);
        long newerItem = insertItem(product, "https://a.example/2", newer);
        long nadav = jdbc.queryForObject(
                "SELECT id FROM app_user WHERE issuer = ? AND sub = 'nadav'", Long.class, PLACEHOLDER_ISSUER);

        Flyway.configure()
                .configuration(flyway.getConfiguration())
                .target("16")
                .load()
                .migrate();

        // Backfill: one row per listing, all Nadav's, added_at copied from created_at (not now()) so
        // the "recently added" order (#226) survives the migration; defaults visible + notifying.
        assertThat(jdbc.queryForList("SELECT user_id, tracked_item_id, hidden, notify_enabled, added_at"
                        + " FROM user_tracked_item ORDER BY added_at DESC"))
                .extracting(
                        r -> r.get("user_id"),
                        r -> r.get("tracked_item_id"),
                        r -> r.get("hidden"),
                        r -> r.get("notify_enabled"),
                        r -> ((java.sql.Timestamp) r.get("added_at")).toInstant())
                .containsExactly(
                        tuple(nadav, newerItem, false, true, newer), tuple(nadav, olderItem, false, true, older));

        // The pair is unique.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO user_tracked_item (user_id, tracked_item_id, added_at) VALUES (?, ?, now())",
                        nadav,
                        olderItem))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_user_tracked_item");

        // The dashboard index is partial on hidden = false.
        assertThat(jdbc.queryForObject(
                        "SELECT indexdef FROM pg_indexes WHERE indexname = 'idx_user_tracked_item_dashboard'",
                        String.class))
                .contains("(user_id, added_at DESC, id DESC)")
                .contains("WHERE (hidden = false)");

        // A catalog delete takes the association with it; the other user's rows are untouched.
        long other = jdbc.queryForObject(
                "INSERT INTO app_user (issuer, sub, created_at) VALUES (?, 'other', now()) RETURNING id",
                Long.class,
                PLACEHOLDER_ISSUER);
        jdbc.update(
                "INSERT INTO user_tracked_item (user_id, tracked_item_id, added_at) VALUES (?, ?, now())",
                other,
                newerItem);
        jdbc.update("DELETE FROM tracked_item WHERE id = ?", olderItem);
        assertThat(linksByUser()).containsExactlyInAnyOrderEntriesOf(Map.of(nadav, 1L, other, 1L));

        // An account delete drops that user's rows and leaves the shared listing alone.
        jdbc.update("DELETE FROM app_user WHERE id = ?", other);
        assertThat(linksByUser()).containsExactlyEntriesOf(Map.of(nadav, 1L));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM tracked_item WHERE id = ?", Long.class, newerItem))
                .isEqualTo(1L);
    }

    private Map<Long, Long> linksByUser() {
        Map<Long, Long> counts = new HashMap<>();
        jdbc.query("SELECT user_id, count(*) AS n FROM user_tracked_item GROUP BY user_id", rs -> {
            counts.put(rs.getLong("user_id"), rs.getLong("n"));
        });
        return counts;
    }

    private long insertProduct(String name) {
        return jdbc.queryForObject(
                "INSERT INTO product (name, created_at) VALUES (?, now()) RETURNING id", Long.class, name);
    }

    private long insertItem(long productId, String url, Instant createdAt) {
        return jdbc.queryForObject(
                "INSERT INTO tracked_item (product_id, url, created_at) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                productId,
                url,
                OffsetDateTime.ofInstant(createdAt, ZoneOffset.UTC));
    }
}
