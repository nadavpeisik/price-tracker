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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductTrackingService {

    @Value("${price.validation.max-delta-percent:200}")
    private int maxDeltaPercent;

    @Value("${price.refresh.min-interval-seconds:60}")
    private int minRefreshIntervalSeconds;

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
        return scrapeAndSave(item);
    }

    @Transactional
    public TrackResponse refreshTrackedItem(Long productId, Long itemId) {
        TrackedItem item = trackedItemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found"));

        if (!item.getProduct().getId().equals(productId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found for this product");
        }

        if (item.getLastChecked() != null &&
                item.getLastChecked().isAfter(LocalDateTime.now().minusSeconds(minRefreshIntervalSeconds))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Item was refreshed recently, try again later");
        }

        return scrapeAndSave(item);
    }

    @Transactional
    public void deleteProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
        productRepository.delete(product);
    }

    @Transactional
    public void deleteTrackedItem(Long productId, Long itemId) {
        TrackedItem item = trackedItemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found"));

        if (!item.getProduct().getId().equals(productId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found for this product");
        }

        trackedItemRepository.delete(item);
    }

    @Transactional
    public ProductResponse updateProduct(Long id, UpdateProductRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

        if (StringUtils.hasText(request.name())) {
            product.setName(request.name());
        }
        if (request.description() != null) {
            product.setDescription(StringUtils.hasText(request.description()) ? request.description() : null);
        }

        productRepository.save(product);
        return new ProductResponse(product.getId(), product.getName(), product.getDescription());
    }

    private TrackResponse scrapeAndSave(TrackedItem item) {
        PriceRecord latest = priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item).orElse(null);

        ScrapeResponse scraped = scraperClient.scrape(item.getUrl());
        if (scraped == null) {
            log.warn("Scraper returned null response for url={}", item.getUrl());
            return buildTrackResponse(item.getProduct(), item, latest);
        }
        PriceInfo info = extractionService.extractPrice(scraped);

        if (!isValidPrice(info, latest)) {
            log.warn("Extracted price failed validation — skipping save. url={} price={} currency={} source={}",
                    item.getUrl(), info.price(), info.currency(), info.extractionSource());
            // intentional: return last known good price rather than an error, so the caller always gets a usable response
            return buildTrackResponse(item.getProduct(), item, latest);
        }

        PriceRecord record = priceRecordRepository.save(PriceRecord.builder()
                .price(info.price())
                .currency(info.currency())
                .available(info.available())
                .extractionSource(info.extractionSource())
                .trackedItem(item)
                .build());

        item.setLastChecked(record.getTimestamp());

        return buildTrackResponse(item.getProduct(), item, record);
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

    private boolean isValidPrice(PriceInfo info, PriceRecord previous) {
        if (info.price() == null || info.price().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Validation failed: price is zero or negative ({})", info.price());
            return false;
        }
        if (info.currency() == null) {
            log.warn("Validation failed: LLM returned null currency");
            return false;
        }
        if (previous == null) return true;
        if (!info.currency().equalsIgnoreCase(previous.getCurrency())) {
            log.warn("Currency changed from {} to {} — skipping delta check", previous.getCurrency(), info.currency());
            return true;
        }

        BigDecimal factor = BigDecimal.valueOf(maxDeltaPercent).divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP).add(BigDecimal.ONE);
        BigDecimal max = previous.getPrice().multiply(factor).setScale(4, RoundingMode.HALF_UP);
        BigDecimal min = previous.getPrice().divide(factor, 4, RoundingMode.HALF_UP);
        if (info.price().compareTo(max) > 0 || info.price().compareTo(min) < 0) {
            log.warn("Validation failed: price {} is outside {}% delta of previous {} {}",
                    info.price(), maxDeltaPercent, previous.getPrice(), previous.getCurrency());
            return false;
        }
        return true;
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
