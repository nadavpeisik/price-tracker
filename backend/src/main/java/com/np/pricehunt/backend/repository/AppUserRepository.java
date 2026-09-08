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
     * The bootstrap account: V15's row, relinked to the owner's real identity by the CLAUDE.md dev
     * bootstrap. What the dev seeder tracks its fixtures for (#246). "Lowest id" is how it is found,
     * not what it means, so the mechanism stays in the derived name and callers ask for the account.
     */
    default Optional<AppUser> findBootstrapAccount() {
        return findFirstByOrderByIdAsc();
    }

    Optional<AppUser> findFirstByOrderByIdAsc();
}
