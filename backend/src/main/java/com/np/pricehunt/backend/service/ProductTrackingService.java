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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
    private final TransactionTemplate transactionTemplate;

    // Per-process refresh rate-limiter. Survives failed scrapes (which never bump DB lastChecked).
    // Single-instance only; pre-Phase-2 Kafka. Lost on restart — acceptable: caller gets one free retry.
    private final Map<Long, Instant> lastRefreshAttempt = new ConcurrentHashMap<>();

    private record ItemSnapshot(Long id, String url) {}

    @Transactional
    public CreateProductResponse createProduct(CreateProductRequest request) {
        Product product = productRepository.save(Product.builder()
                .name(request.name())
                .build());
        return new CreateProductResponse(product.getId(), product.getName());
    }

    public TrackResponse trackUrl(Long productId, TrackRequest request) {
        ItemSnapshot snapshot = transactionTemplate.execute(status -> {
            Product product = productRepository.findById(productId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
            TrackedItem item = resolveTrackedItem(product, request);
            return new ItemSnapshot(item.getId(), item.getUrl());
        });

        PriceInfo info = doScrape(snapshot.url());
        return persistResultInTxn(snapshot.id(), info);
    }

    public TrackResponse refreshTrackedItem(Long productId, Long itemId) {
        ItemSnapshot snapshot = transactionTemplate.execute(status -> {
            TrackedItem item = trackedItemRepository.findById(itemId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found"));
            if (!item.getProduct().getId().equals(productId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found for this product");
            }
            checkAndStampRefreshAttempt(itemId, item.getLastChecked());
            return new ItemSnapshot(item.getId(), item.getUrl());
        });

        PriceInfo info = doScrape(snapshot.url());
        return persistResultInTxn(snapshot.id(), info);
    }

    // Atomically reads and stamps the in-memory rate-limit map. Falls back to the DB-stored
    // lastChecked when the process is fresh and the map is empty. Stamp happens before the
    // scrape, so failed scrapes can't bypass the limit by leaving lastChecked untouched.
    private void checkAndStampRefreshAttempt(Long itemId, LocalDateTime persistedLastChecked) {
        Instant now = Instant.now();
        LocalDateTime nowLdt = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        Instant cutoff = now.minusSeconds(minRefreshIntervalSeconds);

        if (persistedLastChecked != null &&
                persistedLastChecked.isAfter(nowLdt.minusSeconds(minRefreshIntervalSeconds))) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Item was refreshed recently, try again later");
        }

        Instant kept = lastRefreshAttempt.compute(itemId, (k, prev) ->
                (prev != null && prev.isAfter(cutoff)) ? prev : now);
        if (kept != now) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Item was refreshed recently, try again later");
        }
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
        if (request.name() == null && request.description() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At least one field is required");
        }
        if (request.name() != null && !StringUtils.hasText(request.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Name cannot be blank");
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

        if (StringUtils.hasText(request.name())) {
            product.setName(request.name());
        }
        if (request.description() != null) {
            product.setDescription(StringUtils.hasText(request.description()) ? request.description() : null);
        }

        return new ProductResponse(product.getId(), product.getName(), product.getDescription());
    }

    // Phase 2: external HTTP call. Deliberately runs outside any DB transaction so we don't
    // pin a Hikari connection while waiting on the scraper.
    private PriceInfo doScrape(String url) {
        ScrapeResponse scraped = scraperClient.scrape(url);
        if (scraped == null) {
            log.warn("Scraper returned null response for url={}", url);
            return null;
        }
        return extractionService.extractPrice(scraped);
    }

    // Phase 3: validate + persist in a fresh, short-lived transaction.
    private TrackResponse persistResultInTxn(Long itemId, PriceInfo info) {
        return transactionTemplate.execute(status -> {
            TrackedItem item = trackedItemRepository.findById(itemId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found"));
            PriceRecord latest = priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item).orElse(null);

            if (info == null || !isValidPrice(info, latest)) {
                if (info != null) {
                    log.warn("Extracted price failed validation — skipping save. url={} price={} currency={} source={}",
                            item.getUrl(), info.price(), info.currency(), info.extractionSource());
                }
                // intentional: return last known good price rather than an error, so the caller always gets a usable response
                return buildTrackResponse(item.getProduct(), item, latest);
            }

            PriceRecord record = priceRecordRepository.save(PriceRecord.builder()
                    .price(info.price())
                    .currency(info.currency().toUpperCase(Locale.ROOT))
                    .available(info.available())
                    .extractionSource(info.extractionSource())
                    .trackedItem(item)
                    .build());

            item.setLastChecked(record.getTimestamp());

            return buildTrackResponse(item.getProduct(), item, record);
        });
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
                record != null ? record.getTimestamp() : null,
                record != null ? record.getExtractionSource() : null
        );
    }
}
