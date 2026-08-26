package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.Product;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    /**
     * Loads a product under a pessimistic row-level write lock, so listing admission can count and
     * insert without racing a concurrent admission to the same product.
     *
     * <p>A separate method rather than {@code @Lock} on the inherited {@code findById}: that would
     * take the lock for every caller, and most of them only read.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<Product> findForUpdateById(Long id);

    /**
     * Products written by the dev seeder, identified by a reserved description prefix.
     *
     * <p>Prefix rather than "contains" on purpose: the seeder deletes what it finds, and a real
     * product whose description merely mentions the marker must never be swept up.
     */
    List<Product> findByDescriptionStartingWith(String prefix);

    // 2. Search for products by keyword (Great for your future Search Bar!)
    List<Product> findByNameContainingIgnoreCase(String keyword);
}
