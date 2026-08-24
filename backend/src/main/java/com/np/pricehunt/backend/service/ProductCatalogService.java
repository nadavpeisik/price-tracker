package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.CreateProductRequest;
import com.np.pricehunt.backend.dto.CreateProductResponse;
import com.np.pricehunt.backend.dto.ProductResponse;
import com.np.pricehunt.backend.dto.UpdateProductRequest;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Catalogue lifecycle: creating, editing and deleting products, and removing a listing from one.
 * Deliberately separate from {@link ProductTrackingService}, which admits listings and observes
 * prices — the two change for different reasons and share no transaction choreography.
 */
@Service
@RequiredArgsConstructor
public class ProductCatalogService {

    private final ProductRepository productRepository;
    private final TrackedItemRepository trackedItemRepository;

    @Transactional
    public CreateProductResponse createProduct(CreateProductRequest request) {
        Product product =
                productRepository.save(Product.builder().name(request.name()).build());
        return new CreateProductResponse(product.getId(), product.getName());
    }

    @Transactional
    public ProductResponse updateProduct(Long id, UpdateProductRequest request) {
        if (request.name() == null && request.description() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one field is required");
        }
        if (request.name() != null && !StringUtils.hasText(request.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be blank");
        }

        Product product = productRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

        if (StringUtils.hasText(request.name())) {
            product.setName(request.name());
        }
        if (request.description() != null) {
            product.setDescription(StringUtils.hasText(request.description()) ? request.description() : null);
        }

        return new ProductResponse(product.getId(), product.getName(), product.getDescription());
    }

    @Transactional
    public void deleteProduct(Long id) {
        Product product = productRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        productRepository.delete(product);
    }

    @Transactional
    public void deleteTrackedItem(Long productId, Long itemId) {
        TrackedItem item = trackedItemRepository
                .findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found"));

        if (!item.getProduct().getId().equals(productId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found for this product");
        }

        trackedItemRepository.delete(item);
    }
}
