package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.Product;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    // 1. Find a product by its exact name (Case Insensitive)
    Optional<Product> findByNameIgnoreCase(String name);

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
