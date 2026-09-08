package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
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

// Real Postgres for V17 (issue #246). Hibernate's validate sees the table and its columns but not the
// unique constraint, either ON DELETE action, or what the backfill does with the pre-V17 state. So:
// stop Flyway at V16, insert the pre-V17 shapes — a product V16 linked, a product created after V16
// with no join row, an empty product — apply V17, then check every promise.
@JdbcTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
// Not inside @JdbcTest's transaction: the inserted rows would hold locks Flyway's DDL, on its own
// connection, waits for forever. The container is per-class, so nothing needs rolling back.
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class UserProductMigrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> true);
        registry.add("spring.flyway.target", () -> "16");
        registry.add("spring.docker.compose.enabled", () -> false);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Test
    void backfillGivesEveryProductToTheSingleUser_thenTheTableKeepsItsPromises() {
        Instant linkedCreated = Instant.parse("2025-01-10T10:00:00Z");
        Instant laterCreated = Instant.parse("2025-06-01T10:00:00Z");
        Instant emptyCreated = Instant.parse("2025-08-01T10:00:00Z");
        long user = jdbc.queryForObject("SELECT id FROM app_user", Long.class);
        long linked = insertProduct("Headphones", linkedCreated);
        long linkedItem = insertItem(linked, "https://a.example/1", linkedCreated);
        jdbc.update(
                "INSERT INTO user_tracked_item (user_id, tracked_item_id, added_at) VALUES (?, ?, ?)",
                user,
                linkedItem,
                OffsetDateTime.ofInstant(linkedCreated, java.time.ZoneOffset.UTC));
        // Tracked after #244 merged: the old track flow wrote no V16 row for it.
        long later = insertProduct("Keyboard", laterCreated);
        insertItem(later, "https://a.example/2", laterCreated);
        // Created and never tracked: no listing, so no V16 row could ever have pointed at it.
        long empty = insertProduct("Monitor", emptyCreated);

        Flyway.configure()
                .configuration(flyway.getConfiguration())
                .target("17")
                .load()
                .migrate();

        // One membership per product, all the single user's, added_at from the product's created_at.
        List<Map<String, Object>> rows =
                jdbc.queryForList("SELECT user_id, product_id, added_at FROM user_product ORDER BY product_id");
        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> assertThat(((Number) row.get("user_id")).longValue())
                .isEqualTo(user));
        assertThat(rows)
                .extracting(row -> ((Number) row.get("product_id")).longValue())
                .containsExactly(linked, later, empty);
        assertThat(rows)
                .extracting(row -> ((java.sql.Timestamp) row.get("added_at")).toInstant())
                .containsExactly(linkedCreated, laterCreated, emptyCreated);

        // The V16 table is gone, not emptied.
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.tables WHERE table_name = 'user_tracked_item'",
                        Integer.class))
                .isZero();

        // Uniqueness names its constraint, so the handler can map it if a caller ever needs to.
        assertThatThrownBy(() -> jdbc.update(
                        "INSERT INTO user_product (user_id, product_id, added_at) VALUES (?, ?, now())", user, linked))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_user_product");

        // Deleting a product takes its memberships and nothing else; the user row survives.
        jdbc.update("DELETE FROM tracked_item WHERE product_id = ?", later);
        jdbc.update("DELETE FROM product WHERE id = ?", later);
        assertThat(count("user_product", "product_id = " + later)).isZero();
        assertThat(count("user_product", "1 = 1")).isEqualTo(2);
        assertThat(count("app_user", "id = " + user)).isEqualTo(1);

        // Deleting the account takes its memberships; the catalog is untouched.
        jdbc.update("DELETE FROM app_user WHERE id = ?", user);
        assertThat(count("user_product", "1 = 1")).isZero();
        assertThat(count("product", "1 = 1")).isEqualTo(2);
        assertThat(count("tracked_item", "1 = 1")).isEqualTo(1);
    }

    private long insertProduct(String name, Instant createdAt) {
        return jdbc.queryForObject(
                "INSERT INTO product (name, created_at) VALUES (?, ?) RETURNING id",
                Long.class,
                name,
                OffsetDateTime.ofInstant(createdAt, java.time.ZoneOffset.UTC));
    }

    private long insertItem(long productId, String url, Instant createdAt) {
        return jdbc.queryForObject(
                "INSERT INTO tracked_item (product_id, url, created_at) VALUES (?, ?, ?) RETURNING id",
                Long.class,
                productId,
                url,
                OffsetDateTime.ofInstant(createdAt, java.time.ZoneOffset.UTC));
    }

    private int count(String table, String where) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE " + where, Integer.class);
    }
}
