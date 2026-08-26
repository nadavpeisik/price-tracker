package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
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

// Real Postgres (Testcontainers) for the V8 availability_status migration (issue #124). H2 can't be
// trusted with the Postgres-flavored DDL, and the default @DataJpaTest disables Flyway. This test
// loading at all already proves the two things that matter: Flyway applies V1–V8 cleanly on Postgres,
// AND ddl-auto=validate confirms the PriceRecord entity matches the migrated schema
// (varchar(32) availability_status NOT NULL) — i.e. no entity/migration drift. The methods then prove
// the enum round-trips and the CHECK constraint actually constrains.
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
class PriceRecordAvailabilityMigrationTest {

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
    private PriceRecordRepository priceRecordRepository;

    @Autowired
    private TestEntityManager em;

    private TrackedItem persistItem() {
        Product product = em.persistAndFlush(Product.builder().name("Laptop").build());
        return em.persistAndFlush(TrackedItem.builder()
                .url("https://example.com/p")
                .shopName("example.com")
                .product(product)
                .build());
    }

    @ParameterizedTest
    @EnumSource(AvailabilityStatus.class)
    void eachAvailabilityStatusRoundTrips(AvailabilityStatus status) {
        TrackedItem item = persistItem();
        PriceRecord saved = priceRecordRepository.save(PriceRecord.builder()
                .price(new BigDecimal("9.99"))
                .currency("USD")
                .availability(status)
                .extractionSource(ExtractionSource.STRUCTURED)
                .trackedItem(item)
                .observedAt(Instant.now())
                .build());
        em.flush();
        em.clear();

        PriceRecord found = priceRecordRepository.findById(saved.getId()).orElseThrow();
        assertThat(found.getAvailability()).isEqualTo(status);
    }

    @Test
    void checkConstraintRejectsInvalidAvailabilityStatus() {
        TrackedItem item = persistItem();
        Long itemId = item.getId();

        // Native insert bypasses the Java enum so we hit the DB-level CHECK directly (the entity can
        // only ever produce the 3 valid names). A value outside the CHECK list must be rejected.
        EntityManager entityManager = em.getEntityManager();
        assertThatThrownBy(() -> {
                    entityManager
                            .createNativeQuery("INSERT INTO price_record"
                                    + " (price, currency, observed_at, availability_status, extraction_source, tracked_item_id)"
                                    + " VALUES (1.00, 'USD', now(), 'BOGUS', 'STRUCTURED', :itemId)")
                            .setParameter("itemId", itemId)
                            .executeUpdate();
                    entityManager.flush();
                })
                // Tie the failure to the CHECK constraint specifically, so an unrelated SQL error can't
                // false-positive this test (the constraint name surfaces in the Postgres error message).
                .hasStackTraceContaining("price_record_availability_status_check");
    }
}
