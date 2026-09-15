package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.Invitation;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface InvitationRepository extends JpaRepository<Invitation, Long> {

    /**
     * The open invitation for an email, under a row-level write lock: redemption consumes it, and two
     * identities carrying the same verified email must not both get in on one row.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Invitation> findByEmailAndRedeemedAtIsNullAndRevokedAtIsNull(String email);

    /**
     * Revokes an invitation only while it is still open: one atomic statement, so a revoke racing a
     * redemption can neither overwrite the redemption nor need a lock. Returns the rows changed.
     */
    @Modifying
    @Query("UPDATE Invitation i SET i.revokedAt = :now WHERE i.id = :id AND i.redeemedAt IS NULL"
            + " AND i.revokedAt IS NULL")
    int revokeOpen(long id, Instant now);

    List<Invitation> findAllByOrderByCreatedAtDescIdDesc();
}
