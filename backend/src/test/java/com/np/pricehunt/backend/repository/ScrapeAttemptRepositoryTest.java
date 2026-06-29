package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.ScrapeAttempt;
import com.np.pricehunt.backend.domain.ScrapeFailureCode;
import com.np.pricehunt.backend.domain.ScrapeOutcome;
import com.np.pricehunt.backend.domain.TrackedItem;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// Real Postgres (Testcontainers): Flyway runs V1–V9 against the container with ddl-auto=validate, so
// this doubles as the V9 migration/entity gate. It also exercises the survive-everything semantics
// (no FK on tracked_item_id) and the failure_code CHECK — both Postgres-specific behaviours H2 fakes.
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
class ScrapeAttemptRepositoryTest {

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
    private ScrapeAttemptRepository repository;

    @Autowired
    private TestEntityManager em;

    private static ScrapeAttempt attempt(Long trackedItemId, Instant retentionUntil) {
        return ScrapeAttempt.builder()
                .trackedItemId(trackedItemId)
                .url("https://shop.com/p")
                .extractionSource(ExtractionSource.FULLTEXT)
                .outcome(ScrapeOutcome.EXTRACTION_FAILED)
                .failureCode(ScrapeFailureCode.EXTRACTION_ERROR)
                .llmInput("filtered\n$29.99\nin stock")
                .contentHash("c".repeat(64))
                .llmInputHash("d".repeat(64))
                .retentionUntil(retentionUntil)
                .createdAt(Instant.now())
                .build();
    }

    @Test
    void entityValidatesAndRoundTrips() {
        // If the entity drifted from V9, the context would fail to start under ddl-auto=validate.
        ScrapeAttempt saved = repository.save(attempt(1L, Instant.now().plus(90, ChronoUnit.DAYS)));
        em.flush();
        em.clear();

        assertThat(repository.findById(saved.getId())).get().satisfies(a -> {
            assertThat(a.getExtractionSource()).isEqualTo(ExtractionSource.FULLTEXT);
            assertThat(a.getOutcome()).isEqualTo(ScrapeOutcome.EXTRACTION_FAILED);
            assertThat(a.getLlmInput()).isEqualTo("filtered\n$29.99\nin stock");
        });
    }

    @Test
    void findExpiredIds_returnsOnlyExpired_oldestFirst() {
        Instant now = Instant.now();
        ScrapeAttempt expiredOld = repository.save(attempt(1L, now.minus(2, ChronoUnit.DAYS)));
        ScrapeAttempt expiredNew = repository.save(attempt(1L, now.minus(1, ChronoUnit.DAYS)));
        repository.save(attempt(1L, now.plus(1, ChronoUnit.DAYS))); // not expired
        em.flush();

        List<Long> ids = repository.findExpiredIds(now, PageRequest.of(0, 10, Sort.by("retentionUntil", "id")));

        assertThat(ids).containsExactly(expiredOld.getId(), expiredNew.getId());
    }

    @Test
    void attemptSurvivesTrackedItemDeletion_withIdPreserved() {
        // The headline "failures outlive the item" semantic: no FK, so deleting the item leaves the
        // attempt intact with its historical tracked_item_id still set (not nulled, not cascaded).
        Product product = em.persist(Product.builder().name("P").build());
        TrackedItem item = em.persist(
                TrackedItem.builder().url("https://shop.com/p").product(product).build());
        em.flush();
        Long itemId = item.getId();

        ScrapeAttempt saved = repository.save(attempt(itemId, Instant.now().plus(90, ChronoUnit.DAYS)));
        em.flush();

        em.remove(em.find(TrackedItem.class, itemId));
        em.flush();
        em.clear();

        assertThat(em.find(TrackedItem.class, itemId)).isNull();
        assertThat(repository.findById(saved.getId())).get().satisfies(a -> assertThat(a.getTrackedItemId())
                .isEqualTo(itemId));
    }

    @Test
    void failureCodeCheckConstraint_rejectsUnknownValue() {
        // The enum is type-safe in Java; only a raw insert can carry an out-of-enum code, which the
        // CHECK must reject (the failure-only analytics invariant lives in the schema).
        assertThatThrownBy(() -> {
                    em.getEntityManager()
                            .createNativeQuery(
                                    "INSERT INTO scrape_attempt (url, extraction_source, outcome, failure_code, retention_until)"
                                            + " VALUES ('u', 'FULLTEXT', 'EXTRACTION_FAILED', 'BOGUS_CODE', now())")
                            .executeUpdate();
                    em.flush();
                })
                .isInstanceOf(RuntimeException.class);
    }
}
