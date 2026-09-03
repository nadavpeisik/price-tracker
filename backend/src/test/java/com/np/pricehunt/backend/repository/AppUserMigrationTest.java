package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.np.pricehunt.backend.domain.AppUser;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase.Replace;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Real Postgres for V15: the (issuer, sub) unique constraint is the identity key's only enforcement
 * (first-login double-inserts race past any application check), and the backfill row is data only
 * the migration writes. Loading at all gates V1–V15 + ddl-auto=validate for the new entity.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
class AppUserMigrationTest {

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
    private AppUserRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void backfillRowExists_underThePlaceholderIssuer() {
        // The placeholder pair lives under .invalid, which no real IdP can issue — #245 relinks it.
        // Email is NULL until the first real login syncs the verified-email claim (#245).
        assertThat(repository.findByIssuerAndSub("https://auth0-tenant-pending.invalid/", "nadav"))
                .get()
                .extracting(AppUser::getEmail)
                .isNull();
    }

    @Test
    void uniqueConstraintRejectsDuplicateIdentity_namingTheConstraint() {
        repository.saveAndFlush(AppUser.builder()
                .issuer("https://idp.example.com/")
                .sub("auth0|1")
                .build());

        assertThatThrownBy(() -> repository.saveAndFlush(AppUser.builder()
                        .issuer("https://idp.example.com/")
                        .sub("auth0|1")
                        .build()))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasCauseInstanceOf(ConstraintViolationException.class)
                .extracting(e -> ((ConstraintViolationException) e.getCause()).getConstraintName())
                .isEqualTo("uq_app_user_identity");
    }

    @Test
    void createdAtIsImmutableAtTheDatabase() {
        assertThatThrownBy(() ->
                        jdbc.update("UPDATE app_user SET created_at = now() + interval '1 day' WHERE sub = 'nadav'"))
                .hasMessageContaining("created_at is immutable");
    }
}
