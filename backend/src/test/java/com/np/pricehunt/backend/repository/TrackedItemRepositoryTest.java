package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.ShopNameSource;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.repository.projection.DashboardListingRef;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

// Exercises the atomic, rank-guarded applyShopName compare-and-set. JPQL (IN-list + TRIM), so H2 is
// faithful here — the native ON CONFLICT upsert lives in ShopNameMappingRepository (Testcontainers).
@DataJpaTest
@ActiveProfiles("test")
class TrackedItemRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private TrackedItemRepository repo;

    private Product product;

    @BeforeEach
    void setUp() {
        product = em.persist(Product.builder().name("P").build());
    }

    private Long persistItem(String shopName, ShopNameSource source) {
        TrackedItem item = TrackedItem.builder()
                .url("https://example.com/" + System.nanoTime())
                .shopName(shopName)
                .shopNameSource(source)
                .product(product)
                .build();
        em.persist(item);
        em.flush();
        return item.getId();
    }

    private TrackedItem reload(Long id) {
        em.clear();
        return repo.findById(id).orElseThrow();
    }

    @Test
    void hostFillsBlankItem() {
        Long id = persistItem(null, null);

        assertThat(repo.applyShopName(id, "Example", ShopNameSource.HOST_FALLBACK))
                .isTrue();

        TrackedItem r = reload(id);
        assertThat(r.getShopName()).isEqualTo("Example");
        assertThat(r.getShopNameSource()).isEqualTo(ShopNameSource.HOST_FALLBACK);
    }

    @Test
    void hostDoesNotOverwriteExistingLegacyName() {
        // Legacy row: name set by the old code, source still null.
        Long id = persistItem("Legacy Name", null);

        assertThat(repo.applyShopName(id, "Hostguess", ShopNameSource.HOST_FALLBACK))
                .isFalse();
        assertThat(reload(id).getShopName()).isEqualTo("Legacy Name");
    }

    @Test
    void hostDoesNotOverwriteExistingHostName() {
        Long id = persistItem("Example", ShopNameSource.HOST_FALLBACK);

        assertThat(repo.applyShopName(id, "Other", ShopNameSource.HOST_FALLBACK))
                .isFalse();
        assertThat(reload(id).getShopName()).isEqualTo("Example");
    }

    @Test
    void detectedUpgradesHost() {
        Long id = persistItem("example.com", ShopNameSource.HOST_FALLBACK);

        assertThat(repo.applyShopName(id, "Real Shop", ShopNameSource.DETECTED)).isTrue();

        TrackedItem r = reload(id);
        assertThat(r.getShopName()).isEqualTo("Real Shop");
        assertThat(r.getShopNameSource()).isEqualTo(ShopNameSource.DETECTED);
    }

    @Test
    void detectedUpgradesLegacyNullSourceName() {
        Long id = persistItem("legacy.com", null);

        assertThat(repo.applyShopName(id, "Real Shop", ShopNameSource.DETECTED)).isTrue();
        assertThat(reload(id).getShopName()).isEqualTo("Real Shop");
    }

    @Test
    void detectedDoesNotDowngradeMapping() {
        Long id = persistItem("Amazon", ShopNameSource.MAPPING);

        assertThat(repo.applyShopName(id, "Weak Title", ShopNameSource.DETECTED))
                .isFalse();
        assertThat(reload(id).getShopName()).isEqualTo("Amazon");
    }

    @Test
    void mappingUpgradesDetected() {
        Long id = persistItem("Weak", ShopNameSource.DETECTED);

        assertThat(repo.applyShopName(id, "Amazon", ShopNameSource.MAPPING)).isTrue();
        assertThat(reload(id).getShopNameSource()).isEqualTo(ShopNameSource.MAPPING);
    }

    @Test
    void equalRankRefreshesInPlace() {
        Long id = persistItem("Old Detected", ShopNameSource.DETECTED);

        assertThat(repo.applyShopName(id, "New Detected", ShopNameSource.DETECTED))
                .isTrue();
        assertThat(reload(id).getShopName()).isEqualTo("New Detected");
    }

    // --- dashboard projections (#146) ---

    @Test
    void findAllForDashboard_projectsEveryListingWithItsProduct() {
        Product other = em.persist(Product.builder().name("Other").build());
        Long mine = persistItem("Amazon", ShopNameSource.MAPPING);
        Long theirs = persistItemFor(other, "KSP", ShopNameSource.MAPPING);

        assertThat(repo.findAllForDashboard())
                .extracting(
                        DashboardListingRef::trackedItemId,
                        DashboardListingRef::productId,
                        DashboardListingRef::shopName)
                .containsExactly(tuple(mine, product.getId(), "Amazon"), tuple(theirs, other.getId(), "KSP"));
    }

    @Test
    void findAllForDashboard_keepsListingsWithNoShopName() {
        // shop_name is nullable and blank rows exist; the projection must surface them rather than
        // filter them out, so the facet fold sees them and decides (it excludes them from the chips).
        Long unnamed = persistItem(null, null);
        Long blank = persistItem("   ", ShopNameSource.HOST_FALLBACK);

        assertThat(repo.findAllForDashboard())
                .extracting(DashboardListingRef::trackedItemId, DashboardListingRef::shopName)
                .containsExactly(tuple(unnamed, null), tuple(blank, "   "));
    }

    @Test
    void findByProductIdIn_returnsOnlyTheRequestedProductsListings() {
        Product other = em.persist(Product.builder().name("Other").build());
        Long mine = persistItem("Amazon", ShopNameSource.MAPPING);
        persistItemFor(other, "KSP", ShopNameSource.MAPPING);

        assertThat(repo.findByProductIdIn(List.of(product.getId())))
                .extracting(TrackedItem::getId)
                .containsExactly(mine);
    }

    private Long persistItemFor(Product owner, String shopName, ShopNameSource source) {
        TrackedItem item = TrackedItem.builder()
                .url("https://example.com/" + System.nanoTime())
                .shopName(shopName)
                .shopNameSource(source)
                .product(owner)
                .build();
        em.persist(item);
        em.flush();
        return item.getId();
    }

    @Test
    void touchLastChecked_stampsOnlyThatColumn() {
        Long id = persistItem("Example", ShopNameSource.DETECTED);
        Instant at = Instant.parse("2026-08-25T10:00:00Z");

        assertThat(repo.touchLastChecked(id, at)).isTrue();

        TrackedItem reloaded = reload(id);
        assertThat(reloaded.getLastChecked()).isEqualTo(at);
        assertThat(reloaded.getShopName()).isEqualTo("Example");
        assertThat(reloaded.getShopNameSource()).isEqualTo(ShopNameSource.DETECTED);
    }

    @Test
    void touchLastChecked_unknownId_reportsFalse() {
        assertThat(repo.touchLastChecked(999_999L, Instant.now())).isFalse();
    }

    @Test
    void findRefreshViewByIdAndProductId_projectsTheListing() {
        Long id = persistItem("Example", ShopNameSource.DETECTED);

        assertThat(repo.findRefreshViewByIdAndProductId(id, product.getId()))
                .get()
                .satisfies(view -> {
                    assertThat(view.id()).isEqualTo(id);
                    assertThat(view.url()).startsWith("https://example.com/");
                    assertThat(view.lastChecked()).isNull();
                });
    }

    @Test
    void findRefreshViewByIdAndProductId_isAbsentUnderAnotherProduct() {
        Long id = persistItem("Example", ShopNameSource.DETECTED);
        Product other = em.persist(Product.builder().name("Other").build());

        assertThat(repo.findRefreshViewByIdAndProductId(id, other.getId())).isEmpty();
    }
}
