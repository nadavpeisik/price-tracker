package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.CreateProductRequest;
import com.np.pricehunt.backend.dto.CreateProductResponse;
import com.np.pricehunt.backend.dto.ProductResponse;
import com.np.pricehunt.backend.dto.UpdateProductRequest;
import com.np.pricehunt.backend.exception.NotFoundException;
import com.np.pricehunt.backend.exception.ValidationException;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

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
        String name = requireName(request.name());
        Product product = productRepository.save(Product.builder().name(name).build());
        return new CreateProductResponse(product.getId(), product.getName());
    }

    @Transactional
    public ProductResponse updateProduct(Long id, UpdateProductRequest request) {
        if (request.name() == null && request.description() == null) {
            throw new ValidationException("At least one field is required");
        }
        if (request.name() != null && !StringUtils.hasText(request.name())) {
            throw new ValidationException("Name cannot be blank");
        }

        Product product = productRepository.findById(id).orElseThrow(() -> new NotFoundException("Product not found"));

        if (StringUtils.hasText(request.name())) {
            product.setName(request.name().strip());
        }
        if (request.description() != null) {
            product.setDescription(StringUtils.hasText(request.description()) ? request.description() : null);
        }

        return new ProductResponse(product.getId(), product.getName(), product.getDescription());
    }

    /**
     * Uniqueness itself is not checked here: the {@code uq_product_name_ci} index is the only check
     * that holds under concurrent writes, and {@code GlobalExceptionHandler} reports its violation as
     * the 409. What the index cannot see is blank or padded input, so names are validated and stored
     * stripped — "Sony " would otherwise sit beside "Sony".
     */
    private static String requireName(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new ValidationException("Name cannot be blank");
        }
        return raw.strip();
    }

    @Transactional
    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id).orElseThrow(() -> new NotFoundException("Product not found"));
        productRepository.delete(product);
    }

    @Transactional
    public void deleteTrackedItem(Long productId, Long itemId) {
        TrackedItem item = trackedItemRepository
                .findById(itemId)
                .orElseThrow(() -> new NotFoundException("Tracked item not found"));

        if (!item.getProduct().getId().equals(productId)) {
            throw new NotFoundException("Tracked item not found for this product");
        }

        trackedItemRepository.delete(item);
    }
}
