package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.UserTrackedItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

// No finders yet: the per-user flows (#246 dashboard reads, #250 hide/unlink) add each access path
// alongside its caller.
@Repository
public interface UserTrackedItemRepository extends JpaRepository<UserTrackedItem, Long> {}
