package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.client.ScraperClient;
import com.np.pricehunt.backend.config.PriceTrackingProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.ScrapeFailureCode;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.*;
import com.np.pricehunt.backend.money.MoneyPrecision;
import com.np.pricehunt.backend.observability.ScrapeAttemptRecorder;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.repository.projection.TrackedItemRefreshView;
import com.np.pricehunt.backend.service.ratelimit.RefreshCooldownLimiter;
import com.np.pricehunt.backend.util.WireMoney;
import com.np.pricehunt.backend.validator.UrlValidator;
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
    private final RefreshCooldownLimiter cooldownLimiter;
    private final Clock clock;
    // Best-effort audit: a recorder failure must never mask the original tracking failure.
    private final ScrapeAttemptRecorder scrapeAttemptRecorder;
    private final PriceValidator priceValidator;

    // The listing whose price is about to be checked — ids and URL only, never the managed entity.
    private record PriceCheckTarget(Long id, String url) {}

    // Carries the rejection outside the persistence transaction so its REQUIRES_NEW audit runs only
    // after that transaction closes. A non-null rejection IS the rejection; the response is usable
    // either way (last known-good on rejection, freshly saved on acceptance).
    private record PriceCheckOutcome(TrackResponse response, PriceValidator.Rejection rejection) {}

    @Transactional
    public CreateProductResponse createProduct(CreateProductRequest request) {
        Product product =
                productRepository.save(Product.builder().name(request.name()).build());
        return new CreateProductResponse(product.getId(), product.getName());
    }

    public TrackResponse trackUrl(Long productId, TrackRequest request) {
        // A literal JSON `null` body reaches here; an empty body is already a 400 upstream.
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Request body is required");
        }
        // Cheap 404 before the DNS-based validation; admission re-checks under the product lock.
        if (!productRepository.existsById(productId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found");
        }
        // Validate before admission so a rejected URL is never persisted.
        urlValidator.validate(request.url());
        // Serialize listing admission under the product write lock, released before any network I/O.
        PriceCheckTarget target = transactionTemplate.execute(status -> {
            Product product = productRepository
                    .findForUpdateById(productId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));
            TrackedItem item = admitOrReuseListing(product, request);
            return new PriceCheckTarget(item.getId(), item.getUrl());
        });

        // The submitted URL was just validated; avoid a second DNS resolution.
        return checkListingPrice(target, false);
    }

    public TrackResponse refreshTrackedItem(Long productId, Long itemId) {
        TrackedItemRefreshView item = trackedItemRepository
                .findRefreshViewByIdAndProductId(itemId, productId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found"));
        enforcePersistedRefreshCooldown(item.lastChecked());

        // Acquired before scraping, outside any transaction; a failed scrape still consumes the cooldown.
        if (!cooldownLimiter.tryAcquire(item.id())) {
            throw new ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS, "Item was refreshed recently, try again later");
        }

        // Cooldown is consumed before the stored URL is validated, so a rejected refresh burns the window too.
        return checkListingPrice(new PriceCheckTarget(item.id(), item.url()), true);
    }

    // System-initiated refresh: bypasses the rate-limit (the scheduler is the system, not a user).
    public TrackResponse scheduledRefresh(TrackedItemRefreshView item) {
        return checkListingPrice(new PriceCheckTarget(item.id(), item.url()), true);
    }

    // `lastChecked` is the restart-safe half of the cooldown; failed attempts are covered by cooldownLimiter.
    private void enforcePersistedRefreshCooldown(Instant persistedLastChecked) {
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

    // Shared price-check pipeline behind all three entry points. Short DB transactions surround the
    // network work (scrape, then LLM extract) — none is ever held across that I/O — and shop-name
    // changes commit before extraction can fail.
    private TrackResponse checkListingPrice(PriceCheckTarget target, boolean validateStoredUrl) {
        Long itemId = target.id();
        String url = target.url();

        // Refresh paths revalidate the stored URL before any downstream work, outside a transaction
        // because DNS resolution is network I/O.
        if (validateStoredUrl) {
            urlValidator.validate(url);
        }

        // Commit a host-name floor before scraping. A curated mapping is authoritative and skips the
        // post-scrape name work; resolution failure must never block price tracking.
        boolean shopNameCurated = false;
        try {
            shopNameCurated = Boolean.TRUE.equals(transactionTemplate.execute(status -> {
                ShopNameResolver.Resolved resolved = shopNameResolver.resolve(url, null);
                trackedItemRepository.applyShopName(itemId, resolved.name(), resolved.source());
                return resolved.curated();
            }));
        } catch (RuntimeException e) {
            log.warn("Pre-scrape shop-name resolution failed for url={} — proceeding without it", url, e);
        }

        ScrapeResponse scraped = scraperClient.scrape(url);
        if (scraped == null) {
            log.warn("Scraper returned null response for url={}", url);
        }

        // Learn a strong (site-level) proposal before re-resolving so it promotes to MAPPING; a weak
        // <title> proposal stays DETECTED and is never learned. Name persistence is best-effort.
        var proposal = scraped == null ? null : scraped.shopNameProposal();
        if (!shopNameCurated && proposal != null && StringUtils.hasText(proposal.name())) {
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

        // Audit an extraction failure best-effort, then rethrow the original exception (preserves the
        // 502 + scheduler accounting). A null scrape has no evidence to audit.
        PriceInfo info;
        if (scraped == null) {
            info = null;
        } else {
            try {
                info = extractionService.extractPrice(scraped);
            } catch (RuntimeException e) {
                recordExtractionFailureBestEffort(itemId, url, scraped, e);
                throw e;
            }
        }

        // Persist in a short transaction; audit any rejection only after it closes.
        PriceCheckOutcome outcome = validateAndSavePrice(itemId, info);
        if (outcome.rejection() != null && scraped != null) {
            recordValidationRejectionBestEffort(
                    itemId,
                    url,
                    scraped,
                    outcome.rejection().code(),
                    outcome.rejection().detail());
        }
        return outcome.response();
    }

    private PriceCheckOutcome validateAndSavePrice(Long itemId, PriceInfo rawInfo) {
        // Normalize to the persistence scale before validating, so the checks judge exactly what
        // numeric(19,4) stores — a 0.00004 must not pass "price > 0" and land as 0.0000.
        PriceInfo info = rawInfo == null
                ? null
                : new PriceInfo(
                        MoneyPrecision.normalize(rawInfo.price()),
                        rawInfo.currency(),
                        rawInfo.availability(),
                        rawInfo.extractionSource());
        return transactionTemplate.execute(status -> {
            TrackedItem item = trackedItemRepository
                    .findById(itemId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found"));
            PriceRecord latest = priceRecordRepository
                    .findFirstByTrackedItemOrderByTimestampDesc(item)
                    .orElse(null);

            PriceValidator.Rejection rejection = info == null ? null : priceValidator.validate(info, latest);
            if (info == null || rejection != null) {
                if (info != null) {
                    log.warn(
                            "Extracted price failed validation ({}) — skipping save. url={} price={} currency={} source={}",
                            rejection.code(),
                            item.getUrl(),
                            info.price(),
                            info.currency(),
                            info.extractionSource());
                }
                // Missing or rejected extraction: return the last known-good observation rather than an
                // error. A null scrape is not a validation rejection (rejection stays null).
                return new PriceCheckOutcome(toTrackResponse(item.getProduct(), item, latest), rejection);
            }

            // Availability is optional upstream but NOT NULL in storage.
            AvailabilityStatus availability =
                    info.availability() != null ? info.availability() : AvailabilityStatus.UNKNOWN;
            PriceRecord saved = priceRecordRepository.save(PriceRecord.builder()
                    .price(info.price())
                    .currency(info.currency().trim().toUpperCase(Locale.ROOT))
                    .availability(availability)
                    .extractionSource(info.extractionSource())
                    .trackedItem(item)
                    .build());

            item.setLastChecked(saved.getTimestamp());

            log.info(
                    "Tracked itemId={} url={} source={} price={} {} availability={}",
                    item.getId(),
                    item.getUrl(),
                    info.extractionSource(),
                    info.price(),
                    info.currency(),
                    availability);

            return new PriceCheckOutcome(toTrackResponse(item.getProduct(), item, saved), null);
        });
    }

    /**
     * Admits a new listing or reuses the existing one for the URL. The per-product cap applies only
     * to admission of a new listing, never to re-tracking one the product already has. The caller
     * must hold the parent product's write lock, or the count and the insert are not serialized
     * against a concurrent admission.
     */
    private TrackedItem admitOrReuseListing(Product product, TrackRequest request) {
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
                .orElseGet(() -> {
                    long listings = trackedItemRepository.countByProduct(product);
                    if (listings >= trackingProperties.maxListingsPerProduct()) {
                        throw new ResponseStatusException(
                                HttpStatus.CONFLICT,
                                "Listings-per-product limit reached (" + trackingProperties.maxListingsPerProduct()
                                        + ")");
                    }
                    return trackedItemRepository.save(TrackedItem.builder()
                            .url(request.url())
                            .product(product)
                            .build());
                });
    }

    private void recordExtractionFailureBestEffort(
            Long itemId, String url, ScrapeResponse scraped, RuntimeException cause) {
        try {
            scrapeAttemptRecorder.recordExtractionFailure(itemId, url, scraped, cause);
        } catch (RuntimeException recordingError) {
            log.warn(
                    "Failed to record scrape_attempt for extraction failure (url={}): {}",
                    url,
                    recordingError.toString());
        }
    }

    private void recordValidationRejectionBestEffort(
            Long itemId, String url, ScrapeResponse scraped, ScrapeFailureCode code, String detail) {
        try {
            scrapeAttemptRecorder.recordValidationRejection(itemId, url, scraped, code, detail);
        } catch (RuntimeException recordingError) {
            log.warn(
                    "Failed to record scrape_attempt for validation rejection (url={}): {}",
                    url,
                    recordingError.toString());
        }
    }

    private TrackResponse toTrackResponse(Product product, TrackedItem item, PriceRecord latest) {
        return new TrackResponse(
                product.getId(),
                product.getName(),
                item.getId(),
                item.getUrl(),
                item.getShopName(),
                item.getShopNameSource(),
                latest != null ? WireMoney.decimalString(latest.getPrice()) : null,
                latest != null ? latest.getCurrency() : null,
                latest != null ? latest.getAvailability() : AvailabilityStatus.UNKNOWN,
                latest != null ? latest.getTimestamp() : null,
                latest != null ? latest.getExtractionSource() : null);
    }
}
