package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    // Token → user resolution: the (issuer, sub) pair is the identity key (uq_app_user_identity).
    Optional<AppUser> findByIssuerAndSub(String issuer, String sub);

    /**
     * The owner's account: the first one provisioned — on a dev database the hand-relinked V15 row, on a
     * fresh one whoever redeemed the bootstrap invitation (#249). What the dev seeder tracks its fixtures
     * for (#246). "Lowest id" is how it is found, not what it means, so the mechanism stays in the
     * derived name and callers ask for the account.
     */
    default Optional<AppUser> findBootstrapAccount() {
        return findFirstByOrderByIdAsc();
    }

    Optional<AppUser> findFirstByOrderByIdAsc();
}
