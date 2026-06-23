package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.domain.MappingOrigin;
import com.np.pricehunt.backend.domain.ShopNameMapping;
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

// Real Postgres (Testcontainers) so the native ON CONFLICT ... WHERE upsert runs with true Postgres
// semantics — H2 can't honour it. Flyway runs V1–V7 against the container, so this also gates the V7
// migration against the entities (ddl-auto=validate).
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
class ShopNameMappingRepositoryTest {

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
    private ShopNameMappingRepository repository;

    @Autowired
    private TestEntityManager em;

    @Test
    void curatedSeedsAreLoadedByFlyway() {
        assertThat(repository.findByDomain("amazon.com")).get().satisfies(m -> {
            assertThat(m.getDisplayName()).isEqualTo("Amazon");
            assertThat(m.getOrigin()).isEqualTo(MappingOrigin.CURATED);
        });
    }

    @Test
    void upsertInsertsNewLearnedRow() {
        int rows = repository.upsertLearned("newshop.com", "New Shop");
        em.flush();
        em.clear();

        assertThat(rows).isEqualTo(1);
        assertThat(repository.findByDomain("newshop.com")).get().satisfies(m -> {
            assertThat(m.getDisplayName()).isEqualTo("New Shop");
            assertThat(m.getOrigin()).isEqualTo(MappingOrigin.LEARNED);
        });
    }

    @Test
    void upsertUpdatesLearnedRowWhenNameChanges() {
        repository.upsertLearned("rebrand.com", "Old Name");
        em.flush();
        em.clear();

        int rows = repository.upsertLearned("rebrand.com", "New Name");
        em.flush();
        em.clear();

        assertThat(rows).isEqualTo(1);
        assertThat(repository.findByDomain("rebrand.com"))
                .get()
                .extracting(ShopNameMapping::getDisplayName)
                .isEqualTo("New Name");
    }

    @Test
    void upsertIsNoOpWhenLearnedNameUnchanged() {
        repository.upsertLearned("same.com", "Same Name");
        em.flush();
        em.clear();

        int rows = repository.upsertLearned("same.com", "Same Name");

        assertThat(rows).isZero();
    }

    @Test
    void upsertNeverOverwritesCuratedRow() {
        int rows = repository.upsertLearned("amazon.com", "ACME Third-Party Seller");
        em.flush();
        em.clear();

        assertThat(rows).isZero();
        assertThat(repository.findByDomain("amazon.com")).get().satisfies(m -> {
            assertThat(m.getDisplayName()).isEqualTo("Amazon");
            assertThat(m.getOrigin()).isEqualTo(MappingOrigin.CURATED);
        });
    }
}
