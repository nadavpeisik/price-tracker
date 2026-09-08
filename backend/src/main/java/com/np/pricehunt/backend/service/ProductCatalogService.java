package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.config.PriceTrackingProperties;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.CreateProductRequest;
import com.np.pricehunt.backend.dto.CreateProductResponse;
import com.np.pricehunt.backend.dto.ProductResponse;
import com.np.pricehunt.backend.dto.UpdateProductRequest;
import com.np.pricehunt.backend.exception.ConflictException;
import com.np.pricehunt.backend.exception.ErrorCode;
import com.np.pricehunt.backend.exception.NotFoundException;
import com.np.pricehunt.backend.exception.ValidationException;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Catalogue lifecycle: creating, editing and deleting products, and admitting or removing a listing.
 * Knows nothing about users (#246): the shared catalog is the same for everyone, so these are either
 * admin operations (edit, hard delete — gated in {@code SecurityConfig}) or the catalog half of a
 * user action whose membership half {@link ProductTrackingService} records through the tenancy port.
 */
@Service
@RequiredArgsConstructor
public class ProductCatalogService {

    private final ProductRepository productRepository;
    private final TrackedItemRepository trackedItemRepository;
    private final PriceTrackingProperties trackingProperties;

    @Transactional
    public CreateProductResponse createProduct(CreateProductRequest request) {
        String name = requireName(request.name());
        Product product = productRepository.save(Product.builder().name(name).build());
        return new CreateProductResponse(product.getId(), product.getName());
    }

    /**
     * The one catalog-global question the track flow asks: attaching a URL to a product someone else
     * created is the shared-catalog feature, so it is answered before any membership exists. Cheap on
     * purpose — it runs ahead of the DNS-based URL validation so a bad id never costs a resolver slot.
     */
    @Transactional(readOnly = true)
    public boolean productExists(Long productId) {
        return productRepository.existsById(productId);
    }

    /**
     * Admits a new listing or reuses the existing one for the URL, under the parent product's write
     * lock so the count and the insert are serialized against a concurrent admission. The per-product
     * cap applies only to admission of a new listing, never to re-tracking one the product already has.
     * Joins the caller's transaction when one is open, so membership can commit with admission.
     *
     * @return the listing's id; the caller already holds its URL, since a reused listing is matched on
     *     that exact string
     */
    @Transactional
    public Long admitListing(Long productId, String url) {
        Product product = productRepository
                .findForUpdateById(productId)
                .orElseThrow(() -> new NotFoundException("Product not found"));
        TrackedItem item = trackedItemRepository
                .findByUrl(url)
                .map(existing -> {
                    if (!existing.getProduct().getId().equals(product.getId())) {
                        throw new ConflictException(
                                ErrorCode.URL_TRACKED_BY_ANOTHER_PRODUCT,
                                "URL already tracked under product: "
                                        + existing.getProduct().getName());
                    }
                    return existing;
                })
                .orElseGet(() -> {
                    long listingCount = trackedItemRepository.countByProduct(product);
                    if (listingCount >= trackingProperties.maxListingsPerProduct()) {
                        throw new ConflictException(
                                ErrorCode.PRODUCT_LISTING_LIMIT_REACHED,
                                "Listings-per-product limit reached (" + trackingProperties.maxListingsPerProduct()
                                        + ")");
                    }
                    return trackedItemRepository.save(
                            TrackedItem.builder().url(url).product(product).build());
                });
        return item.getId();
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

    /** Admin only: removes the catalog row for everyone; the database cascades memberships (V17). */
    @Transactional
    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id).orElseThrow(() -> new NotFoundException("Product not found"));
        productRepository.delete(product);
    }

    /** Admin only: a user who no longer wants a product uses stop-tracking, which touches no catalog row. */
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
