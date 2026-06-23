package com.np.pricehunt.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Domain → display-name mapping: both curated marketplace/platform seeds and auto-learned names,
 * keyed on the PSL registrable domain (e.g. {@code amazon.com}, {@code ksp.co.il}). One row per
 * domain. {@code origin} protects curated rows from the self-healing learn upsert.
 */
@Entity
@Table(
        name = "shop_name_mapping",
        uniqueConstraints = @UniqueConstraint(name = "uq_shop_name_mapping_domain", columnNames = "domain"))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopNameMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String domain;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(length = 32, nullable = false)
    private MappingOrigin origin;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // Defensive: the production learn() path writes via a native ON CONFLICT upsert (which sets
    // updated_at in SQL), but any JpaRepository.save() must never send a null updated_at.
    @PrePersist
    @PreUpdate
    void touchUpdatedAt() {
        this.updatedAt = Instant.now();
    }
}
