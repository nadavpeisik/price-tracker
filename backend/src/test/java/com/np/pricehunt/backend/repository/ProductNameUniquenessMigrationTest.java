package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.np.pricehunt.backend.domain.Product;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

// Real Postgres for the V13 unique-name index: the service does no uniqueness check of its own, so
// this is the rule's only enforcement and the only place it can be proven. The assertion also pins
// the exception shape GlobalExceptionHandler reads (Hibernate's ConstraintViolationException in the
// cause chain, carrying the index name). Loading at all gates V1–V13 + ddl-auto=validate.
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
class ProductNameUniquenessMigrationTest {

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
    private ProductRepository repository;

    @Test
    void uniqueIndexRejectsCaseInsensitiveDuplicate_namingTheIndex() {
        repository.saveAndFlush(Product.builder().name("Sony WH-1000XM5").build());

        assertThatThrownBy(() -> repository.saveAndFlush(
                        Product.builder().name("sony wh-1000xm5").build()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasCauseInstanceOf(ConstraintViolationException.class)
                .extracting(e -> ((ConstraintViolationException) e.getCause()).getConstraintName())
                .isEqualTo("uq_product_name_ci");
    }
}
