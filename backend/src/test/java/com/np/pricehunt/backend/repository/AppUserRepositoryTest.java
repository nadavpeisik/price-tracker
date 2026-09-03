package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.domain.AppUser;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * The identity lookup used by token → user resolution (#245): the finder must match on BOTH halves
 * of the pair, because sub values collide across providers and issuer alone names everyone on one.
 */
@DataJpaTest
@ActiveProfiles("test")
class AppUserRepositoryTest {

    private static final String ISSUER = "https://dev-tenant.us.auth0.com/";
    private static final String SUB = "auth0|123456";

    @Autowired
    private AppUserRepository repository;

    @Autowired
    private TestEntityManager em;

    private AppUser persistUser(String issuer, String sub) {
        AppUser saved = repository.saveAndFlush(AppUser.builder()
                .issuer(issuer)
                .sub(sub)
                .email("user@example.com")
                .build());
        em.clear();
        return saved;
    }

    @Test
    void findByIssuerAndSub_matchesTheExactPair() {
        AppUser saved = persistUser(ISSUER, SUB);

        assertThat(repository.findByIssuerAndSub(ISSUER, SUB))
                .get()
                .extracting(AppUser::getId)
                .isEqualTo(saved.getId());
    }

    @Test
    void findByIssuerAndSub_sameSubOtherIssuer_isEmpty() {
        persistUser(ISSUER, SUB);

        assertThat(repository.findByIssuerAndSub("https://other-idp.example.com/", SUB))
                .isEmpty();
    }

    @Test
    void findByIssuerAndSub_sameIssuerOtherSub_isEmpty() {
        persistUser(ISSUER, SUB);

        assertThat(repository.findByIssuerAndSub(ISSUER, "auth0|999999")).isEmpty();
    }

    // created_at: same audit contract as product/tracked_item (V12).

    @Test
    void createdAtIsStampedOnInsertWhenAbsent() {
        Instant before = Instant.now();
        AppUser saved = persistUser(ISSUER, SUB);
        assertThat(saved.getCreatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void createdAtSurvivesAnUpdate() {
        AppUser saved = persistUser(ISSUER, SUB);
        saved = repository.findById(saved.getId()).orElseThrow();
        Instant stamped = saved.getCreatedAt();
        saved.setCreatedAt(Instant.parse("2000-01-01T00:00:00Z"));
        saved.setEmail("renamed@example.com");
        repository.saveAndFlush(saved);
        em.clear();
        assertThat(repository.findById(saved.getId()).orElseThrow().getCreatedAt())
                .isEqualTo(stamped);
    }
}
