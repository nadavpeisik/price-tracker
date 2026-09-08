package com.np.pricehunt.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One user's membership in one product (epic #241, issue #246): "user U tracks product P". The
 * catalog is shared, so tracking a URL admits the listing once for everyone and adds one row here;
 * every shop under the product, now or later, shows through this row. Removing a product from a
 * user's dashboard is deleting this row and nothing else. The database, not JPA, owns the cascades
 * (V17): {@code @OnDelete} only tells Hibernate so, which is what the H2 suites generate DDL from.
 */
@Entity
@Table(
        name = "user_product",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_user_product",
                        columnNames = {"user_id", "product_id"}),
        indexes = @Index(name = "idx_user_product_product", columnList = "product_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Product product;

    /** When this user started tracking the product — the per-user "recently added" key (#226). */
    @Column(nullable = false, updatable = false)
    private Instant addedAt;

    @PrePersist
    protected void onCreate() {
        if (addedAt == null) {
            addedAt = Instant.now();
        }
    }
}
