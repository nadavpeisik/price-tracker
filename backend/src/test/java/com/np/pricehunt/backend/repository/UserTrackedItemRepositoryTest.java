package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.domain.AppUser;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.domain.UserTrackedItem;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

/**
 * The association's contract in JPA terms: it stamps its own added_at, and — the one that matters
 * for multi-user — deleting it leaves the catalog row in place. Postgres-only behaviour (the backfill, the cascades, the partial index) is in
 * {@link UserTrackedItemMigrationTest}.
 */
@DataJpaTest
@ActiveProfiles("test")
class UserTrackedItemRepositoryTest {

    @Autowired
    private UserTrackedItemRepository repository;

    @Autowired
    private TrackedItemRepository trackedItemRepository;

    @Autowired
    private TestEntityManager em;

    private AppUser user;
    private TrackedItem item;

    @BeforeEach
    void catalogAndUser() {
        user = em.persist(AppUser.builder()
                .issuer("https://idp.example.com/")
                .sub("auth0|1")
                .build());
        Product product = em.persist(Product.builder().name("Headphones").build());
        item = em.persist(TrackedItem.builder()
                .product(product)
                .url("https://shop.example/hp")
                .build());
        em.flush();
    }

    private UserTrackedItem link() {
        UserTrackedItem saved = repository.saveAndFlush(
                UserTrackedItem.builder().user(user).trackedItem(item).build());
        em.clear();
        return saved;
    }

    @Test
    void defaultsAreVisibleAndNotifying_andAddedAtIsStamped() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MICROS);

        UserTrackedItem saved = repository.findById(link().getId()).orElseThrow();

        assertThat(saved.isHidden()).isFalse();
        assertThat(saved.isNotifyEnabled()).isTrue();
        assertThat(saved.getAddedAt()).isAfterOrEqualTo(before);
    }

    @Test
    void addedAtSurvivesAnUpdate() {
        UserTrackedItem saved = repository.findById(link().getId()).orElseThrow();
        Instant stamped = saved.getAddedAt();

        saved.setAddedAt(Instant.parse("2000-01-01T00:00:00Z"));
        saved.setHidden(true);
        repository.saveAndFlush(saved);
        em.clear();

        UserTrackedItem reloaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(reloaded.getAddedAt()).isEqualTo(stamped);
        assertThat(reloaded.isHidden()).isTrue();
    }

    @Test
    void unlinkDeletesOnlyTheAssociation_theListingStays() {
        UserTrackedItem saved = link();

        repository.deleteById(saved.getId());
        repository.flush();
        em.clear();

        assertThat(repository.findById(saved.getId())).isEmpty();
        assertThat(trackedItemRepository.findById(item.getId())).isPresent();
    }
}
