package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.client.ScraperClient;
import com.np.pricehunt.backend.config.PriceTrackingProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.*;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.service.ratelimit.RefreshCooldownLimiter;
import com.np.pricehunt.backend.validator.UrlValidator;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@RequiredArgsConstructor
public class ProductTrackingService {

    private final ProductRepository productRepository;
    private final TrackedItemRepository trackedItemRepository;
    private final PriceRecordRepository priceRecordRepository;
    private final PriceExtractionService extractionService;
    private final ScraperClient scraperClient;
    private final TransactionTemplate transactionTemplate;
    private final UrlValidator urlValidator;
    private final PriceTrackingProperties trackingProperties;
    private final ShopNameResolver shopNameResolver;
    // Volatile, per-process half of the refresh cooldown: catches rapid retries (incl. after a
    // failed scrape, which never bumps DB lastChecked). The durable half is the lastChecked check
    // below. Single-instance only, pre-Phase-2; a Redis-backed impl swaps in behind this interface.
    private final RefreshCooldownLimiter cooldownLimiter;
    private final Clock clock;

    private record ItemSnapshot(Long id, String url) {}

    @Transactional
    public CreateProductResponse createProduct(CreateProductRequest request) {
        Product product =
                productRepository.save(Product.builder().name(request.name()).build());
        return new CreateProductResponse(product.getId(), product.getName());
    }

    public TrackResponse trackUrl(Long productId, TrackRequest request) {
        urlValidator.validate(request.url());
        ItemSnapshot snapshot = transactionTemplate.execute(status -> {
            Product product = productRepository
                    .findById(productId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
            TrackedItem item = resolveTrackedItem(product, request);
            return new ItemSnapshot(item.getId(), item.getUrl());
        });

        return trackAndPersist(snapshot.id(), snapshot.url());
    }

    public TrackResponse refreshTrackedItem(Long productId, Long itemId) {
        // Phase 1: load + authorize the item and apply the durable (DB-persisted) cooldown.
        ItemSnapshot snapshot = transactionTemplate.execute(status -> {
            TrackedItem item = trackedItemRepository
                    .findById(itemId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found"));
            if (!item.getProduct().getId().equals(productId)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found for this product");
            }
            ensureNotRecentlyChecked(item.getLastChecked());
            return new ItemSnapshot(item.getId(), item.getUrl());
        });

        // Volatile cooldown: stamped before the scrape so a failed scrape still consumes the
        // window. Kept outside the transaction above — no DB connection is held across this check.
        if (!cooldownLimiter.tryAcquire(snapshot.id())) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Item was refreshed recently, try again later");
        }

        return trackAndPersist(snapshot.id(), snapshot.url());
    }

    // System-initiated refresh: bypasses the rate-limit (the scheduler is the system, not a user).
    public TrackResponse scheduledRefresh(Long itemId) {
        ItemSnapshot snapshot = transactionTemplate.execute(status -> {
            TrackedItem item = trackedItemRepository
                    .findById(itemId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found"));
            return new ItemSnapshot(item.getId(), item.getUrl());
        });
        return trackAndPersist(snapshot.id(), snapshot.url());
    }

    // Durable half of the refresh cooldown: a successful refresh persists lastChecked, which
    // survives a restart. Rejects before any scrape. The volatile half (failed-scrape retries)
    // is handled by cooldownLimiter in refreshTrackedItem.
    private void ensureNotRecentlyChecked(Instant persistedLastChecked) {
        Instant cutoff = Instant.now(clock).minus(trackingProperties.minRefreshInterval());
        if (persistedLastChecked != null && persistedLastChecked.isAfter(cutoff)) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Item was refreshed recently, try again later");
        }
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

    // Shared pipeline for all three entry points after their own auth/cooldown gating. Three short
    // DB transactions bracket the two network calls (scrape, then LLM extract) — a transaction is
    // never held across that I/O. Shop-name resolution is committed before price extraction, so a
    // price failure never loses the name.
    private TrackResponse trackAndPersist(Long itemId, String url) {
        // Step 1 — pre-scrape tx: ensure a name floor (host only fills a blank) and detect whether
        // the domain resolves to a curated row (authoritative → skip post-scrape name work).
        // Best-effort like step 3: a name-resolution failure here must never block price tracking,
        // so it is logged and we proceed (curatedLocked = false).
        boolean curatedLocked = false;
        try {
            curatedLocked = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                ShopNameResolver.Resolved resolved = shopNameResolver.resolve(url, null);
                trackedItemRepository.applyShopName(itemId, resolved.name(), resolved.source());
                return resolved.curated();
            }));
        } catch (RuntimeException e) {
            log.warn("Pre-scrape shop-name resolution failed for url={} — proceeding without it", url, e);
        }

        // Step 2 — scrape (network, no tx).
        ScrapeResponse scraped = scraperClient.scrape(url);
        if (scraped == null) {
            log.warn("Scraper returned null response for url={}", url);
        }

        // Step 3 — name tx (DB only): skipped when curated-locked, the scrape failed, or no name was
        // proposed (the step-1 floor already stands). A strong (site-level) proposal is learned into
        // the shared mapping first, so the re-resolve promotes it to MAPPING; a weak <title> proposal
        // stays DETECTED and is never learned. Best-effort: a name-DB failure is logged here, never
        // aborting the price update that follows.
        var proposal = scraped == null ? null : scraped.shopNameProposal();
        if (!curatedLocked && proposal != null && StringUtils.hasText(proposal.name())) {
            try {
                if (proposal.strong()) {
                    shopNameResolver.learn(url, proposal.name());
                }
                transactionTemplate.executeWithoutResult(status -> {
                    ShopNameResolver.Resolved resolved = shopNameResolver.resolve(url, proposal.name());
                    trackedItemRepository.applyShopName(itemId, resolved.name(), resolved.source());
                });
            } catch (RuntimeException e) {
                log.warn("Shop-name learn/resolve failed for url={} — keeping the pre-scrape name", url, e);
            }
        }

        // Step 4 — price extraction (network, may call the LLM). Failures propagate as before; the
        // name is already committed.
        PriceInfo info = scraped == null ? null : extractionService.extractPrice(scraped);

        // Step 5 — validate + persist the price in a fresh, short-lived transaction.
        return persistResultInTxn(itemId, info);
    }

    private TrackResponse persistResultInTxn(Long itemId, PriceInfo info) {
        return transactionTemplate.execute(status -> {
            TrackedItem item = trackedItemRepository
                    .findById(itemId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found"));
            PriceRecord latest = priceRecordRepository
                    .findFirstByTrackedItemOrderByTimestampDesc(item)
                    .orElse(null);

            if (info == null || !isValidPrice(info, latest)) {
                if (info != null) {
                    log.warn(
                            "Extracted price failed validation — skipping save. url={} price={} currency={} source={}",
                            item.getUrl(),
                            info.price(),
                            info.currency(),
                            info.extractionSource());
                }
                // intentional: return last known good price rather than an error, so the caller always gets a usable
                // response
                return buildTrackResponse(item.getProduct(), item, latest);
            }

            // Defense at the persistence boundary: availability is optional metadata, so coalesce a
            // null to UNKNOWN before the NOT NULL write rather than relying on an upstream guarantee.
            AvailabilityStatus availability =
                    info.availability() != null ? info.availability() : AvailabilityStatus.UNKNOWN;
            PriceRecord record = priceRecordRepository.save(PriceRecord.builder()
                    .price(info.price())
                    .currency(info.currency().toUpperCase(Locale.ROOT))
                    .availability(availability)
                    .extractionSource(info.extractionSource())
                    .trackedItem(item)
                    .build());

            item.setLastChecked(record.getTimestamp());

            log.info(
                    "Tracked itemId={} url={} source={} price={} {} availability={}",
                    item.getId(),
                    item.getUrl(),
                    info.extractionSource(),
                    info.price(),
                    info.currency(),
                    info.availability());

            return buildTrackResponse(item.getProduct(), item, record);
        });
    }

    private TrackedItem resolveTrackedItem(Product product, TrackRequest request) {
        return trackedItemRepository
                .findByUrl(request.url())
                .map(existing -> {
                    if (!existing.getProduct().getId().equals(product.getId())) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "URL already tracked under product: "
                                        + existing.getProduct().getName());
                    }
                    return existing;
                })
                .orElseGet(() -> trackedItemRepository.save(TrackedItem.builder()
                        .url(request.url())
                        .product(product)
                        .build()));
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

        BigDecimal factor = BigDecimal.valueOf(trackingProperties.maxDeltaPercent())
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                .add(BigDecimal.ONE);
        BigDecimal max = previous.getPrice().multiply(factor).setScale(4, RoundingMode.HALF_UP);
        BigDecimal min = previous.getPrice().divide(factor, 4, RoundingMode.HALF_UP);
        if (info.price().compareTo(max) > 0 || info.price().compareTo(min) < 0) {
            log.warn(
                    "Validation failed: price {} is outside {}% delta of previous {} {}",
                    info.price(), trackingProperties.maxDeltaPercent(), previous.getPrice(), previous.getCurrency());
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
                item.getShopNameSource(),
                record != null ? record.getPrice() : null,
                record != null ? record.getCurrency() : null,
                record != null ? record.getAvailability() : AvailabilityStatus.UNKNOWN,
                record != null ? record.getTimestamp() : null,
                record != null ? record.getExtractionSource() : null);
    }
}
