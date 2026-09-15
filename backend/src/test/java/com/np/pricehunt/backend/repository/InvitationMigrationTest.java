package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.np.pricehunt.backend.domain.Invitation;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
 * Real Postgres for V18 (#249): the partial unique index, the normalization CHECK and the created_at
 * trigger exist only in the migration — Hibernate's {@code validate} sees columns, and H2's
 * create-drop builds none of them — so this is their one pin. Loading at all gates the entity mapping
 * (the explicit {@code invited_by} / {@code redeemed_by} column names) against the real schema.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
class InvitationMigrationTest {

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
    private InvitationRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private static Invitation open(String email) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        return Invitation.builder()
                .email(email)
                .createdAt(now)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build();
    }

    @Test
    void secondOpenInvitationForAnEmail_isRejected_namingTheIndex() {
        repository.saveAndFlush(open("guest@example.com"));

        assertThatThrownBy(() -> repository.saveAndFlush(open("guest@example.com")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasCauseInstanceOf(ConstraintViolationException.class)
                .extracting(e -> ((ConstraintViolationException) e.getCause()).getConstraintName())
                .isEqualTo("uq_invitation_open_email");
    }

    @Test
    void aRevokedInvitation_andANewOpenOne_coexist() {
        Invitation revoked = open("guest@example.com");
        revoked.setRevokedAt(Instant.now());
        repository.saveAndFlush(revoked);

        Invitation replacement = repository.saveAndFlush(open("guest@example.com"));

        assertThat(replacement.getId()).isNotNull();
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void aMixedCaseEmail_isRejectedAtTheDatabase() {
        // The bootstrap row is typed by hand; the CHECK is what turns a typo into an error instead of
        // an invitation that never matches.
        assertThatThrownBy(() -> jdbc.update("INSERT INTO invitation (email, created_at, expires_at)"
                        + " VALUES ('Guest@Example.com', now(), now() + interval '7 days')"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_invitation_email_normalized");
    }

    @Test
    void createdAtIsImmutableAtTheDatabase() {
        Invitation saved = repository.saveAndFlush(open("guest@example.com"));

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE invitation SET created_at = now() + interval '1 day' WHERE id = ?", saved.getId()))
                .hasMessageContaining("created_at is immutable");
    }
}
