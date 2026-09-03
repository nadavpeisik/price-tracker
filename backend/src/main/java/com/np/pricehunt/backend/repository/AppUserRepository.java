package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    // Token → user resolution: the (issuer, sub) pair is the identity key (uq_app_user_identity).
    Optional<AppUser> findByIssuerAndSub(String issuer, String sub);
}
