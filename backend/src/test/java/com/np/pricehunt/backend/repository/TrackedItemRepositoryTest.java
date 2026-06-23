package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.ShopNameSource;
import com.np.pricehunt.backend.domain.TrackedItem;
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
}
