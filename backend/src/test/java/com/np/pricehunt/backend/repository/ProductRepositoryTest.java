package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.domain.Product;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * The locking finder used by listing admission.
 *
 * <p>Two things need pinning that the service's Mockito tests cannot reach: that {@code
 * findForUpdateById} is a valid derived name at all, and that {@code @Lock} actually takes effect.
 * Dropping the annotation would leave every existing test green.
 */
@DataJpaTest
@ActiveProfiles("test")
class ProductRepositoryTest {

    @Autowired
    private ProductRepository repository;

    @Autowired
    private TestEntityManager em;

    private Product persistProduct() {
        Product saved = repository.saveAndFlush(Product.builder().name("Locked").build());
        // Force the finder to actually load, rather than returning the row already in the context.
        em.clear();
        return saved;
    }

    @Test
    void findForUpdateById_derivesToAnIdLookup() {
        // Spring Data treats text between `find` and `By` as descriptive, so `ForUpdate` is discarded
        // and only `Id` becomes the predicate. A misderived name fails at repository creation.
        Product saved = persistProduct();

        assertThat(repository.findForUpdateById(saved.getId()))
                .get()
                .extracting(Product::getId)
                .isEqualTo(saved.getId());
    }

    @Test
    void findForUpdateById_missingId_isEmpty() {
        assertThat(repository.findForUpdateById(999_999L)).isEmpty();
    }

    @Test
    void findForUpdateById_holdsAPessimisticWriteLock() {
        Product saved = persistProduct();

        Product locked = repository.findForUpdateById(saved.getId()).orElseThrow();

        assertThat(em.getEntityManager().getLockMode(locked)).isEqualTo(LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void findById_takesNoLock_soOrdinaryReadersAreUnaffected() {
        // The reason the locking finder is a separate method rather than @Lock on the inherited one.
        Product saved = persistProduct();

        Product plain = repository.findById(saved.getId()).orElseThrow();

        assertThat(em.getEntityManager().getLockMode(plain)).isEqualTo(LockModeType.NONE);
    }

    // created_at (#225): stamped once on insert, kept when supplied, and never touched by updates.

    @Test
    void createdAtIsStampedOnInsertWhenAbsent() {
        Instant before = Instant.now();
        Product saved = repository.saveAndFlush(Product.builder().name("Fresh").build());
        assertThat(saved.getCreatedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void createdAtSuppliedByCallerIsKept() {
        Instant backDated = Instant.parse("2025-01-01T00:00:00Z");
        Product saved = repository.saveAndFlush(
                Product.builder().name("Seeded").createdAt(backDated).build());
        em.clear();
        assertThat(repository.findById(saved.getId()).orElseThrow().getCreatedAt())
                .isEqualTo(backDated);
    }

    @Test
    void createdAtSurvivesAnUpdate() {
        Product saved = repository.saveAndFlush(Product.builder().name("Before").build());
        // Compare against the stored value, not the in-memory one: Instant.now() carries nanos on
        // Linux and the column keeps micros, so the two differ there (CI) but not on macOS.
        em.clear();
        saved = repository.findById(saved.getId()).orElseThrow();
        Instant stamped = saved.getCreatedAt();
        saved.setCreatedAt(Instant.parse("2000-01-01T00:00:00Z"));
        saved.setName("After");
        repository.saveAndFlush(saved);
        em.clear();
        assertThat(repository.findById(saved.getId()).orElseThrow().getCreatedAt())
                .isEqualTo(stamped);
    }
}
