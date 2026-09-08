package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.np.pricehunt.backend.domain.AppUser;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.domain.UserProduct;
import com.np.pricehunt.backend.repository.projection.DashboardListingRef;
import com.np.pricehunt.backend.repository.projection.TrackedProductRef;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

/**
 * The membership entity's mapping and its JPQL finders on H2 (#246). The native queries (the latest-
 * observation join and the {@code ON CONFLICT} insert) and the cascades are Postgres semantics, and live
 * in {@code UserScopedCatalogTest} and {@code UserProductMigrationTest} on Testcontainers.
 */
@DataJpaTest
@ActiveProfiles("test")
class UserProductRepositoryTest {

    @Autowired
    private UserProductRepository repository;

    @Autowired
    private TestEntityManager em;

    private AppUser alice;
    private AppUser bob;
    private Product shared;
    private Product alicesOnly;
    private TrackedItem sharedKsp;
    private TrackedItem sharedBug;

    @BeforeEach
    void seed() {
        alice = em.persist(
                AppUser.builder().issuer("https://t.invalid/").sub("alice").build());
        bob = em.persist(
                AppUser.builder().issuer("https://t.invalid/").sub("bob").build());
        shared = em.persist(Product.builder().name("Shared").build());
        alicesOnly = em.persist(Product.builder().name("Alice only").build());
        Product bobsOnly = em.persist(Product.builder().name("Bob only").build());
        sharedKsp = em.persist(TrackedItem.builder()
                .url("https://a/1")
                .shopName("KSP")
                .product(shared)
                .build());
        sharedBug = em.persist(TrackedItem.builder()
                .url("https://a/2")
                .shopName("Bug")
                .product(shared)
                .build());
        em.persist(TrackedItem.builder()
                .url("https://a/3")
                .shopName("Ivory")
                .product(bobsOnly)
                .build());
        em.persist(UserProduct.builder().user(alice).product(shared).build());
        em.persist(UserProduct.builder().user(alice).product(alicesOnly).build());
        em.persist(UserProduct.builder().user(bob).product(shared).build());
        em.persist(UserProduct.builder().user(bob).product(bobsOnly).build());
        em.flush();
        em.clear();
    }

    @Test
    void addedAtIsStampedOnInsert_andTheUserProductPairIsUnique() {
        UserProduct saved = repository.saveAndFlush(UserProduct.builder()
                .user(em.find(AppUser.class, alice.getId()))
                .product(em.find(
                        Product.class,
                        em.persistAndFlush(Product.builder().name("New").build())
                                .getId()))
                .build());
        assertThat(saved.getAddedAt()).isNotNull().isBeforeOrEqualTo(Instant.now());

        assertThatThrownBy(() -> repository.saveAndFlush(UserProduct.builder()
                        .user(em.find(AppUser.class, alice.getId()))
                        .product(em.find(Product.class, shared.getId()))
                        .build()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void trackedProducts_areTheCallersOnly_inIdOrder() {
        assertThat(repository.findTrackedProducts(alice.getId()))
                .extracting(TrackedProductRef::name)
                .containsExactly("Shared", "Alice only");
    }

    @Test
    void listingsOfTrackedProducts_includeEveryListingUnderTheCallersProducts_evenOnesAddedByOthers() {
        // Shared visibility is the feature: Alice sees every shop under a product she tracks.
        assertThat(repository.findListingsOfTrackedProducts(alice.getId()))
                .extracting(
                        DashboardListingRef::trackedItemId,
                        DashboardListingRef::productId,
                        DashboardListingRef::shopName)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(sharedKsp.getId(), shared.getId(), "KSP"),
                        org.assertj.core.groups.Tuple.tuple(sharedBug.getId(), shared.getId(), "Bug"));
    }

    @Test
    void findTrackedProduct_resolvesAProductWithNoListings_andHidesOneTheCallerDoesNotTrack() {
        assertThat(repository.findTrackedProduct(alice.getId(), alicesOnly.getId()))
                .isPresent()
                .get()
                .satisfies(ref -> assertThat(ref.name()).isEqualTo("Alice only"));
        assertThat(repository.findTrackedProduct(bob.getId(), alicesOnly.getId()))
                .isEmpty();
        assertThat(repository.findTrackedProduct(alice.getId(), 999_999L)).isEmpty();
    }

    @Test
    void findListing_needsTheCallersMembership_andTheItemUnderThatProduct() {
        assertThat(repository.findListing(alice.getId(), shared.getId(), sharedKsp.getId()))
                .isPresent()
                .get()
                .satisfies(ref -> {
                    assertThat(ref.url()).isEqualTo("https://a/1");
                    assertThat(ref.shopName()).isEqualTo("KSP");
                });
        // Right item, wrong product in the path: absent, same as not yours.
        assertThat(repository.findListing(alice.getId(), alicesOnly.getId(), sharedKsp.getId()))
                .isEmpty();
    }

    @Test
    void stopTracking_deletesOnlyTheCallersRow() {
        assertThat(repository.stopTracking(alice.getId(), shared.getId())).isEqualTo(1);
        assertThat(repository.stopTracking(alice.getId(), shared.getId())).isZero();
        em.clear();

        assertThat(repository.findTrackedProducts(alice.getId()))
                .extracting(TrackedProductRef::name)
                .containsExactly("Alice only");
        assertThat(repository.findTrackedProducts(bob.getId()))
                .extracting(TrackedProductRef::name)
                .containsExactly("Shared", "Bob only");
    }
}
