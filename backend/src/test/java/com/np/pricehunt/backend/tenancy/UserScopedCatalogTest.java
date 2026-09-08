package com.np.pricehunt.backend.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.repository.AppUserRepository;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.UserProductRepository;
import com.np.pricehunt.backend.repository.projection.DashboardListingRef;
import com.np.pricehunt.backend.repository.projection.ListingLatestObservationRow;
import com.np.pricehunt.backend.repository.projection.TrackedProductRef;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * The port's Postgres-only paths (#246): the {@code ON CONFLICT} membership insert, the native
 * latest-observation join, and that every read answers empty for the wrong user. Real Postgres like
 * {@code ShopNameMappingRepositoryTest}, and Flyway with {@code validate} so this doubles as a V17 gate.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = Replace.NONE)
@Testcontainers
class UserScopedCatalogTest {

    private static final Instant NOW = Instant.parse("2026-03-20T12:00:00Z");

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
    private UserProductRepository userProducts;

    @Autowired
    private PriceRecordRepository priceRecords;

    @Autowired
    private AppUserRepository appUsers;

    @Autowired
    private TestEntityManager em;

    private UserScopedCatalog catalog;
    private long alice;
    private long bob;
    private Product shared;
    private TrackedItem sharedKsp;

    @BeforeEach
    void seed() {
        catalog = new UserScopedCatalog(userProducts, priceRecords, Clock.fixed(NOW, ZoneOffset.UTC));
        alice = TestTenants.admit(appUsers, "auth0|alice").getId();
        bob = TestTenants.admit(appUsers, "auth0|bob").getId();
        shared =
                em.persist(Product.builder().name("Shared " + System.nanoTime()).build());
        sharedKsp = em.persist(TrackedItem.builder()
                .url("https://ksp.example/" + System.nanoTime())
                .shopName("KSP")
                .product(shared)
                .build());
        em.persist(PriceRecord.builder()
                .trackedItem(sharedKsp)
                .price(new BigDecimal("100.0000"))
                .currency("ILS")
                .availability(AvailabilityStatus.AVAILABLE)
                .extractionSource(ExtractionSource.STRUCTURED)
                .observedAt(NOW.minusSeconds(3600))
                .build());
        em.flush();
        em.clear();
    }

    @Test
    void track_isIdempotent_andKeepsTheOriginalAddedAt() {
        catalog.track(alice, shared.getId());
        Instant first = addedAt(alice);

        // Re-track (a second URL under the same product, say): no error, no timestamp bump.
        new UserScopedCatalog(userProducts, priceRecords, Clock.fixed(NOW.plusSeconds(600), ZoneOffset.UTC))
                .track(alice, shared.getId());

        assertThat(addedAt(alice)).isEqualTo(first).isEqualTo(NOW);
        assertThat(catalog.trackedProducts(alice))
                .extracting(TrackedProductRef::productId)
                .containsExactly(shared.getId());
    }

    @Test
    void everyRead_answersEmptyForAUserWhoDoesNotTrackTheProduct() {
        catalog.track(alice, shared.getId());

        assertThat(catalog.trackedProducts(bob)).isEmpty();
        assertThat(catalog.listingsOfTrackedProducts(bob)).isEmpty();
        assertThat(catalog.trackedProduct(bob, shared.getId())).isEmpty();
        assertThat(catalog.listings(bob, shared.getId())).isEmpty();
        assertThat(catalog.listing(bob, shared.getId(), sharedKsp.getId())).isEmpty();
        assertThat(catalog.listingsWithLatestObservation(bob, shared.getId(), NOW))
                .isEmpty();
        assertThat(catalog.priceHistory(bob, shared.getId(), sharedKsp.getId(), NOW.minusSeconds(86_400), NOW))
                .isEmpty();
        assertThat(catalog.stopTracking(bob, shared.getId())).isFalse();
    }

    @Test
    void reads_answerForTheUserWhoTracksTheProduct() {
        catalog.track(alice, shared.getId());

        assertThat(catalog.listings(alice, shared.getId()))
                .extracting(DashboardListingRef::shopName)
                .containsExactly("KSP");
        assertThat(catalog.listingsWithLatestObservation(alice, shared.getId(), NOW))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getTrackedItemId()).isEqualTo(sharedKsp.getId());
                    assertThat(row.getPrice()).isEqualByComparingTo("100");
                    assertThat(row.getObservedAt()).isEqualTo(NOW.minusSeconds(3600));
                });
        assertThat(catalog.priceHistory(alice, shared.getId(), sharedKsp.getId(), NOW.minusSeconds(86_400), NOW))
                .isPresent()
                .get()
                .satisfies(history -> {
                    assertThat(history.listing().shopName()).isEqualTo("KSP");
                    assertThat(history.records()).hasSize(1);
                });
    }

    @Test
    void latestObservationJoin_keepsListingsWithNoObservation() {
        catalog.track(alice, shared.getId());
        em.persist(TrackedItem.builder()
                .url("https://bug.example/" + System.nanoTime())
                .shopName("Bug")
                .product(shared)
                .build());
        em.flush();

        assertThat(catalog.listingsWithLatestObservation(alice, shared.getId(), NOW))
                .extracting(ListingLatestObservationRow::getShopName, row -> row.getPrice() == null)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("KSP", false),
                        org.assertj.core.groups.Tuple.tuple("Bug", true));
    }

    @Test
    void stopTracking_removesOnlyTheCallersMembership() {
        catalog.track(alice, shared.getId());
        catalog.track(bob, shared.getId());

        assertThat(catalog.stopTracking(alice, shared.getId())).isTrue();

        assertThat(catalog.trackedProduct(alice, shared.getId())).isEmpty();
        assertThat(catalog.trackedProduct(bob, shared.getId())).isPresent();
        assertThat(em.find(Product.class, shared.getId())).isNotNull();
    }

    private Instant addedAt(long userId) {
        return em.getEntityManager()
                .createQuery(
                        "SELECT up.addedAt FROM UserProduct up WHERE up.user.id = :u AND up.product.id = :p",
                        Instant.class)
                .setParameter("u", userId)
                .setParameter("p", shared.getId())
                .getSingleResult();
    }
}
