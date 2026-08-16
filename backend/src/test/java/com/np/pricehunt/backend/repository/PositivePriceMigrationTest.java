package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * V11 has to survive the database it was written for (issue #175).
 *
 * <p>The zero-price rows this migration cleans up are, by construction, only present on databases
 * that already have a frozen listing. Adding the CHECK constraint without deleting them first would
 * abort startup on exactly those installations — so the migration meant to fix the bug would instead
 * stop the app from booting, and the self-healing validation would never get to run. Both local
 * reviewers flagged that independently; this pins it.
 *
 * <p>The first test migrates to V10, plants a legacy {@code 0.0000} row the way an older build would
 * have, and only then runs V11 — the sequence a real upgrade performs and the one a fresh-database
 * test can never exercise. The container is deliberately <b>per-method</b>: these tests need
 * different starting schema versions, so sharing one database would make them order-dependent.
 */
@Testcontainers
class PositivePriceMigrationTest {

    @Container
    private final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:17"));

    private DataSource dataSource() {
        PGSimpleDataSource ds = new PGSimpleDataSource();
        ds.setUrl(postgres.getJdbcUrl());
        ds.setUser(postgres.getUsername());
        ds.setPassword(postgres.getPassword());
        return ds;
    }

    private void migrateUpTo(String version) {
        Flyway.configure()
                .dataSource(dataSource())
                .locations("classpath:db/migration")
                .target(version)
                .load()
                .migrate();
    }

    @Test
    void v11ClearsALegacyZeroPriceRowInsteadOfRefusingToStart() throws SQLException {
        migrateUpTo("10");
        seedProductAndListing();
        seedPrice("0.0000", "2 days");
        seedPrice("49.9900", "3 days");

        assertThat(count("select count(*) from price_record where price <= 0")).isEqualTo(1);

        // The migration under test. Before the DELETE was added, this threw and aborted startup.
        migrateUpTo("11");

        assertThat(count("select count(*) from price_record where price <= 0")).isZero();
        // Only the invalid row goes; a healthy observation on the same listing is untouched.
        assertThat(count("select count(*) from price_record")).isEqualTo(1);
    }

    @Test
    void v11ResetsLastCheckedWhenTheListingLosesItsOnlyObservation() throws SQLException {
        migrateUpTo("10");
        seedProductAndListing();
        seedPrice("0.0000", "2 days");
        execute("update tracked_item set last_checked = now() - interval '2 days'");

        migrateUpTo("11");

        // Null, not stale: the scheduler selects `last_checked IS NULL OR < cutoff`, so the listing
        // goes back in the queue immediately instead of waiting out its refresh window.
        assertThat(count("select count(*) from tracked_item where last_checked is null"))
                .isEqualTo(1);
    }

    @Test
    void v11RewindsLastCheckedToTheNewestSurvivingObservation() throws SQLException {
        migrateUpTo("10");
        seedProductAndListing();
        seedPrice("49.9900", "3 days");
        seedPrice("0.0000", "2 days");
        execute("update tracked_item set last_checked = now() - interval '2 days'");

        migrateUpTo("11");

        // A listing that still has history keeps a truthful timestamp rather than being nulled.
        assertThat(
                        count(
                                """
                        select count(*) from tracked_item t
                        where t.last_checked = (select max(p.timestamp) from price_record p
                                                where p.tracked_item_id = t.id)
                        """))
                .isEqualTo(1);
    }

    @Test
    void v11AlsoClearsNaN_whichTheGreaterThanZeroCheckWouldOtherwiseAccept() throws SQLException {
        migrateUpTo("10");
        seedProductAndListing();
        seedPrice("'NaN'::numeric", "2 days");

        migrateUpTo("11");

        assertThat(count("select count(*) from price_record")).isZero();
    }

    @Test
    void theConstraintRejectsNaN_becausePostgresSortsItAboveEveryNumber() throws SQLException {
        migrateUpTo("11");
        seedProductAndListing();

        assertThatThrownBy(() -> seedPrice("'NaN'::numeric", "1 day"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("price_record_price_positive");
    }

    @Test
    void theConstraintRejectsANonPositivePriceWrittenOutsideTheApplication() throws SQLException {
        migrateUpTo("11");
        seedProductAndListing();

        assertThatThrownBy(() -> seedPrice("0.0000", "1 day"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("price_record_price_positive");
    }

    private void seedProductAndListing() throws SQLException {
        execute("insert into product (name) values ('Keychron K8 Pro')");
        execute(
                """
                insert into tracked_item (url, shop_name, product_id)
                values ('https://bestbuy.com/site/p/6TEST.p', 'bestbuy.com', (select id from product limit 1))
                """);
    }

    /** Written directly, bypassing the application — the shape an older build persisted. */
    private void seedPrice(String price, String ago) throws SQLException {
        execute(
                """
                insert into price_record (price, currency, timestamp, availability_status,
                                          extraction_source, tracked_item_id)
                values (%s, 'USD', now() - interval '%s', 'AVAILABLE', 'STRUCTURED',
                        (select id from tracked_item limit 1))
                """
                        .formatted(price, ago));
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private long count(String sql) throws SQLException {
        try (Connection connection = dataSource().getConnection();
                Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
