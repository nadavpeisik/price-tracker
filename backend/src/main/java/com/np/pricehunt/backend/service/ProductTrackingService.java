package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.client.ScraperClient;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.*;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.net.URISyntaxException;

@Service
@RequiredArgsConstructor
public class ProductTrackingService {

    private final ProductRepository productRepository;
    private final TrackedItemRepository trackedItemRepository;
    private final PriceRecordRepository priceRecordRepository;
    private final PriceExtractionService extractionService;
    private final ScraperClient scraperClient;

    @Transactional
    public CreateProductResponse createProduct(CreateProductRequest request) {
        Product product = productRepository.save(Product.builder()
                .name(request.name())
                .build());
        return new CreateProductResponse(product.getId(), product.getName());
    }

    @Transactional
    public TrackResponse trackUrl(Long productId, TrackRequest request) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

        TrackedItem item = resolveTrackedItem(product, request);

        String innerText = scraperClient.scrape(request.url());
        PriceInfo info = extractionService.extractPrice(innerText);

        PriceRecord record = priceRecordRepository.save(PriceRecord.builder()
                .price(info.price())
                .currency(info.currency())
                .available(info.available())
                .trackedItem(item)
                .build());

        return buildTrackResponse(product, item, record);
    }

    @Transactional
    public TrackResponse updateTrackedItem(Long productId, Long itemId, UpdateTrackedItemRequest request) {
        TrackedItem item = trackedItemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found"));

        if (!item.getProduct().getId().equals(productId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found for this product");
        }

        if (StringUtils.hasText(request.shopName())) {
            item.setShopName(request.shopName());
            trackedItemRepository.save(item);
        }

        PriceRecord latest = priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item).orElse(null);
        return buildTrackResponse(item.getProduct(), item, latest);
    }

    private TrackedItem resolveTrackedItem(Product product, TrackRequest request) {
        return trackedItemRepository.findByUrl(request.url())
                .map(existing -> {
                    if (!existing.getProduct().getId().equals(product.getId())) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT,
                                "URL already tracked under product: " + existing.getProduct().getName());
                    }
                    return existing;
                })
                .orElseGet(() -> trackedItemRepository.save(TrackedItem.builder()
                        .url(request.url())
                        .shopName(resolveShopName(request.url(), request.shopName()))
                        .product(product)
                        .build()));
    }

    private String resolveShopName(String url, String providedShopName) {
        if (StringUtils.hasText(providedShopName)) return providedShopName;
        try {
            String host = new URI(url).getHost();
            if (host == null) return url;
            return host.replaceFirst("^www\\.", "");
        } catch (URISyntaxException e) {
            return url;
        }
    }

    private TrackResponse buildTrackResponse(Product product, TrackedItem item, PriceRecord record) {
        return new TrackResponse(
                product.getId(),
                product.getName(),
                item.getId(),
                item.getUrl(),
                item.getShopName(),
                record != null ? record.getPrice() : null,
                record != null ? record.getCurrency() : null,
                record != null && record.isAvailable(),
                record != null ? record.getTimestamp() : null
        );
    }
}
