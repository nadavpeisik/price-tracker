package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {

    // 1. Find a product by its exact name (Case Insensitive)
    Optional<Product> findByNameIgnoreCase(String name);

    // 2. Search for products by keyword (Great for your future Search Bar!)
    List<Product> findByNameContainingIgnoreCase(String keyword);
}
