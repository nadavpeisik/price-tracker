package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.ShopNameMapping;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ShopNameMappingRepository extends JpaRepository<ShopNameMapping, Long> {

    // Read path for resolve(): look up the shop name for a registrable domain.
    Optional<ShopNameMapping> findByDomain(String domain);

    /**
     * Self-healing, curated-protected upsert for the learn() path. Inserts a new {@code LEARNED}
     * row, or — only when the existing row is {@code LEARNED} and the name actually changed (site
     * rebrand) — refreshes it. A {@code CURATED} row is never touched (the {@code origin='LEARNED'}
     * guard) and an unchanged {@code LEARNED} row is a no-op (the {@code display_name <> EXCLUDED}
     * guard). Native because JPA/JPQL cannot express {@code ON CONFLICT … WHERE}; requires the
     * caller to run inside a transaction.
     *
     * @return rows affected (1 = inserted/updated, 0 = curated-protected or no-op)
     */
    @Modifying
    @Query(
            value =
                    """
                    INSERT INTO shop_name_mapping (domain, display_name, origin, updated_at)
                    VALUES (:domain, :displayName, 'LEARNED', now())
                    ON CONFLICT (domain) DO UPDATE
                        SET display_name = EXCLUDED.display_name, updated_at = now()
                        WHERE shop_name_mapping.origin = 'LEARNED'
                          AND shop_name_mapping.display_name <> EXCLUDED.display_name
                    """,
            nativeQuery = true)
    int upsertLearned(@Param("domain") String domain, @Param("displayName") String displayName);
}
