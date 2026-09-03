package com.np.pricehunt.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * An application user, keyed on the OIDC pair {@code (issuer, sub)} — the only identity an identity
 * provider guarantees stable. Email is a mutable attribute (reassignable at the provider), never a
 * key; the internal {@code id} is what the rest of the schema references, so a provider migration
 * relinks {@code (issuer, sub)} without touching any owning rows.
 */
@Entity
@Table(
        name = "app_user",
        uniqueConstraints =
                @UniqueConstraint(
                        name = "uq_app_user_identity",
                        columnNames = {"issuer", "sub"}))
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String issuer;

    @Column(nullable = false)
    private String sub;

    private String email;

    /** ISO-4217 code, or null for "no preference". */
    @Column(name = "display_currency", length = 3)
    private String displayCurrency;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
