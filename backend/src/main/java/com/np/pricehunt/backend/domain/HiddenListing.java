package com.np.pricehunt.backend.domain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

/**
 * One membership hiding one shop (epic #241, issue #250): the row's presence is the whole state, so
 * there is no flag to keep in step with it. A child of {@link UserProduct}, not of the user, so
 * stop-tracking cascades every hide away (V19) and a later re-track starts clean. Read only through
 * the outer joins on {@code UserProductRepository}; written only through its native statements.
 * {@code @OnDelete} mirrors the database cascades for the H2 suites, as on {@link UserProduct}.
 */
@Entity
@Table(
        name = "hidden_listing",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_hidden_listing",
                        columnNames = {"user_product_id", "tracked_item_id"}),
        indexes = @Index(name = "idx_hidden_listing_item", columnList = "tracked_item_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HiddenListing {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_product_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private UserProduct userProduct;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tracked_item_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private TrackedItem trackedItem;
}
