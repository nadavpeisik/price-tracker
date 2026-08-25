package com.np.pricehunt.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
// Postgres does not index a foreign key automatically; every product -> listings walk needs this (V10).
@Table(indexes = {@Index(name = "idx_tracked_item_product", columnList = "product_id")})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TrackedItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String url;
    private String shopName;

    // Which tier produced shopName (MAPPING > DETECTED > HOST_FALLBACK). Nullable: legacy rows
    // predate detection and resolve on their next refresh. Maps tracked_item.shop_name_source (V7).
    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private ShopNameSource shopNameSource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id")
    private Product product;

    private Instant lastChecked;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "trackedItem", cascade = CascadeType.ALL)
    private List<PriceRecord> priceHistory;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
