package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.client.ScraperClient;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.ScrapeFailureCode;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.PriceInfo;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import com.np.pricehunt.backend.dto.TrackResponse;
import com.np.pricehunt.backend.exception.NotFoundException;
import com.np.pricehunt.backend.money.MoneyPrecision;
import com.np.pricehunt.backend.observability.ScrapeAttemptRecorder;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.repository.projection.TrackedItemRefreshView;
import com.np.pricehunt.backend.util.WireMoney;
import com.np.pricehunt.backend.validator.UrlValidator;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The price-check pipeline behind every entry point — name floor → scrape → name from page → extract
 * → validate + save → audit — for one listing identified by id and URL. Short DB transactions
 * surround the network work (scrape, then LLM extract) and none is ever held across that I/O; the
 * shop name is committed before extraction can fail ({@link ShopNameAssignment} explains why naming
 * rides along with the price check at all).
 *
 * <p>An engine, not a user-facing service (#246): it never asks who the caller is. The scheduler
 * calls it for the whole catalog, and {@link ProductTrackingService} calls it only after the tenancy
 * port has proven the caller may act on the listing — which is what lets this class hold the raw
 * repositories while anything holding a {@code CurrentUser} may not.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PriceCheckPipeline {

    private final TrackedItemRepository trackedItemRepository;
    private final PriceRecordRepository priceRecordRepository;
    private final PriceExtractionService extractionService;
    private final ScraperClient scraperClient;
    private final TransactionTemplate transactionTemplate;
    private final UrlValidator urlValidator;
    private final ShopNameAssignment shopNameAssignment;
    // Best-effort audit: a recorder failure must never mask the original tracking failure.
    private final ScrapeAttemptRecorder scrapeAttemptRecorder;
    private final PriceValidator priceValidator;

    // Carries the rejection outside the persistence transaction so its REQUIRES_NEW audit runs only
    // after that transaction closes. A non-null rejection IS the rejection; the response is usable
    // either way (last known-good on rejection, freshly saved on acceptance).
    private record PriceCheckOutcome(TrackResponse response, PriceValidator.Rejection rejection) {}

    /** System-initiated refresh: the scheduler is the system, not a user, so no cooldown applies. */
    public TrackResponse scheduledRefresh(TrackedItemRefreshView item) {
        return checkPrice(item.id(), item.url(), true);
    }

    /**
     * @param url the listing's URL, which every caller already holds — admission matches a reused
     *     listing on that exact string, and both refresh paths carry it on the row they resolved
     * @param validateStoredUrl refresh paths revalidate the stored URL before any downstream work
     *     (outside a transaction: DNS resolution is network I/O); the track path already validated
     *     the submitted URL and skips the second resolution
     */
    public TrackResponse checkPrice(Long listingId, String url, boolean validateStoredUrl) {
        if (validateStoredUrl) {
            urlValidator.validate(url);
        }

        boolean shopNameCurated = shopNameAssignment.applyNameFromUrl(listingId, url);

        ScrapeResponse scraped = scraperClient.scrape(url);
        if (scraped == null) {
            log.warn("Scraper returned null response for url={}", url);
        } else if (!shopNameCurated) {
            // A curated mapping is final; only then does the page get a say.
            shopNameAssignment.applyNameFromPage(listingId, url, scraped.shopNameProposal());
        }

        PriceInfo info = scraped == null ? null : extractPriceWithFailureAudit(listingId, url, scraped);

        // Persist in a short transaction; audit any rejection only after it closes.
        PriceCheckOutcome outcome = validateAndSavePrice(listingId, info);
        if (outcome.rejection() != null && scraped != null) {
            recordValidationRejectionBestEffort(
                    listingId,
                    url,
                    scraped,
                    outcome.rejection().code(),
                    outcome.rejection().detail());
        }
        return outcome.response();
    }

    // Audit the failure best-effort, then rethrow the original exception (preserves the 502 and the
    // scheduler's accounting).
    private PriceInfo extractPriceWithFailureAudit(Long itemId, String url, ScrapeResponse scraped) {
        try {
            return extractionService.extractPrice(scraped);
        } catch (RuntimeException e) {
            recordExtractionFailureBestEffort(itemId, url, scraped, e);
            throw e;
        }
    }

    // Normalize to the persistence scale before validating, so the checks judge exactly what
    // numeric(19,4) stores — a 0.00004 must not pass "price > 0" and land as 0.0000.
    private static PriceInfo normalizeForPersistence(PriceInfo raw) {
        return raw == null
                ? null
                : new PriceInfo(
                        MoneyPrecision.normalize(raw.price()),
                        raw.currency(),
                        raw.availability(),
                        raw.extractionSource());
    }

    private PriceCheckOutcome validateAndSavePrice(Long itemId, PriceInfo rawInfo) {
        PriceInfo info = normalizeForPersistence(rawInfo);
        return transactionTemplate.execute(status -> {
            TrackedItem item = trackedItemRepository
                    .findById(itemId)
                    .orElseThrow(() -> new NotFoundException("Tracked item not found"));
            PriceRecord latest = priceRecordRepository
                    .findFirstByTrackedItemOrderByObservedAtDesc(item)
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

            // By id, never item.setLastChecked(...): a dirty entity flushes every column, so this is the
            // one write in the pipeline that could overwrite a concurrent shop-name change (#222).
            if (trackedItemRepository.updateLastCheckedById(itemId, saved.getObservedAt()) == 0) {
                log.warn("lastChecked not stamped — tracked item {} vanished mid-check", itemId);
            }

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

    private static TrackResponse toTrackResponse(Product product, TrackedItem item, PriceRecord latest) {
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
                latest != null ? latest.getObservedAt() : null,
                latest != null ? latest.getExtractionSource() : null);
    }
}
