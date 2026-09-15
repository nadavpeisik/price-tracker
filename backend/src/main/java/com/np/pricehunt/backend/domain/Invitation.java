package com.np.pricehunt.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An invitation to hold an account (issue #249), keyed on a normalized email and consumed once: the
 * open row for an email is the one redemption locks, and {@code redeemedAt} is written exactly once.
 * The two account references are plain ids rather than associations: no reader needs the rows, and the
 * writer already holds both ids when it writes them. The partial unique index, the CHECK and the trigger
 * live only in V18 — Hibernate's {@code validate} sees columns, not those.
 */
@Entity
@Table(name = "invitation")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Invitation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    @Column(name = "invited_by")
    private Long invitedById;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant expiresAt;

    private Instant revokedAt;

    private Instant redeemedAt;

    @Column(name = "redeemed_by")
    private Long redeemedById;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
