package com.np.pricehunt.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One user's relationship to one canonical listing (epic #241). The catalog is shared — a URL is
 * scraped once however many users watch it — so everything per user (hidden from their dashboard,
 * wants notifications, when they added it) lives on this association row. Removing a listing from
 * a user's view is deleting this row and nothing else: there is deliberately no cascade into the
 * catalog, and the database, not JPA, cleans these rows up when a catalog row or account goes
 * (V16, {@code ON DELETE CASCADE}).
 */
@Entity
@Table(
        name = "user_tracked_item",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_user_tracked_item",
                        columnNames = {"user_id", "tracked_item_id"}),
        // The reverse lookup only; the partial dashboard index (hidden = false) has no JPA
        // spelling and lives in V16 alone.
        indexes = @Index(name = "idx_user_tracked_item_item", columnList = "tracked_item_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserTrackedItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private AppUser user;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tracked_item_id", nullable = false)
    private TrackedItem trackedItem;

    @Column(nullable = false)
    private boolean hidden;

    /** Column is notify_enabled, not notify: NOTIFY is a Postgres keyword. */
    @Builder.Default
    @Column(name = "notify_enabled", nullable = false)
    private boolean notifyEnabled = true;

    /** The per-user "recently added" key (#226); the catalog's created_at is not that (#225). */
    @Column(nullable = false, updatable = false)
    private Instant addedAt;

    @PrePersist
    protected void onCreate() {
        if (addedAt == null) {
            addedAt = Instant.now();
        }
    }
}
