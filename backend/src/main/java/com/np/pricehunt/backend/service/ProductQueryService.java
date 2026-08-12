package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.config.PriceHistoryProperties;
import com.np.pricehunt.backend.config.PriceTrendProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.*;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.service.fx.ConvertedAmount;
import com.np.pricehunt.backend.service.fx.PriceConverter;
import com.np.pricehunt.backend.service.trend.TrendEligibility;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ProductQueryService {

    private final ProductRepository productRepository;
    private final TrackedItemRepository trackedItemRepository;
    private final PriceRecordRepository priceRecordRepository;
    private final PriceConverter priceConverter;
    private final PriceHistoryProperties historyProperties;
    private final PriceTrendProperties trendProperties;
    private final Clock clock;

    private record ListingWithLatestPrice(TrackedItem item, PriceRecord latest) {}

    private record ListingWithConvertedPrice(ListingWithLatestPrice source, ConvertedAmount converted) {}

    public Page<ProductSummaryResponse> getAllProducts(Pageable pageable, String displayCurrency) {
        // displayCurrency support is validated in ProductController before this method is called.
        // N+1: O(pageSize × storesPerProduct). Acceptable at current scale; revisit with JPQL fetch join when traffic
        // grows.
        Instant now = clock.instant();
        return productRepository.findAll(pageable).map(product -> {
            List<ListingWithLatestPrice> allListings = fetchItemsWithLatestPricesAsOf(product, now);
            return toSummaryResponse(product, allListings, displayCurrency, now);
        });
    }

    public ProductDetailResponse getProduct(Long id) {
        Product product = productRepository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

        List<TrackedItemSummary> summaries = fetchItemsWithLatestPrices(product).stream()
                .map(p -> toItemSummary(p.item(), p.latest()))
                .toList();

        return new ProductDetailResponse(product.getId(), product.getName(), product.getDescription(), summaries);
    }

    public PriceHistoryResponse getPriceHistory(Long productId, Long itemId, Instant from, Instant to) {
        TrackedItem item = trackedItemRepository
                .findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found"));

        if (!item.getProduct().getId().equals(productId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found for this product");
        }

        Instant effectiveTo = (to != null) ? to : Instant.now();
        Instant effectiveFrom =
                (from != null) ? from : effectiveTo.minus(historyProperties.defaultWindowDays(), ChronoUnit.DAYS);

        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "'from' timestamp cannot be after 'to' timestamp");
        }

        Instant maxFrom = effectiveTo.minus(365L * 2, ChronoUnit.DAYS);
        if (effectiveFrom.isBefore(maxFrom)) {
            log.info("Price history range clamped to 2 years for item={}", itemId);
            effectiveFrom = maxFrom;
        }

        List<PriceRecord> records = priceRecordRepository.findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(
                item, effectiveFrom, effectiveTo);

        List<PricePointResponse> history = records.stream()
                .map(r -> new PricePointResponse(
                        r.getPrice(),
                        r.getCurrency(),
                        r.getAvailability(),
                        r.getTimestamp(),
                        r.getExtractionSource().name()))
                .toList();

        return new PriceHistoryResponse(item.getId(), item.getShopName(), item.getUrl(), history);
    }

    private List<ListingWithLatestPrice> fetchItemsWithLatestPrices(Product product) {
        return trackedItemRepository.findByProduct(product).stream()
                .map(item -> new ListingWithLatestPrice(
                        item,
                        priceRecordRepository
                                .findFirstByTrackedItemOrderByTimestampDesc(item)
                                .orElse(null)))
                .toList();
    }

    /**
     * List-only variant that ignores records stamped after {@code asOfInstant}.
     *
     * <p>Kept separate from {@link #fetchItemsWithLatestPrices} so the product-detail endpoint keeps
     * reporting each listing's raw latest observation unchanged: only the summary row's best-price
     * selection adopts the trend engine's eligibility rules.
     */
    private List<ListingWithLatestPrice> fetchItemsWithLatestPricesAsOf(Product product, Instant asOfInstant) {
        return trackedItemRepository.findByProduct(product).stream()
                .map(item -> new ListingWithLatestPrice(
                        item,
                        priceRecordRepository
                                .findFirstByTrackedItemAndTimestampLessThanEqualOrderByTimestampDescIdDesc(
                                        item, asOfInstant)
                                .orElse(null)))
                .toList();
    }

    private ProductSummaryResponse toSummaryResponse(
            Product product, List<ListingWithLatestPrice> allListings, String displayCurrency, Instant asOfInstant) {
        int storeCount = allListings.size();

        List<ListingWithLatestPrice> pricedListings =
                allListings.stream().filter(p -> p.latest() != null).toList();

        // Rollup over ALL trackers (not just priced ones): a never-checked tracker (latest == null)
        // is UNKNOWN, so "one UNAVAILABLE priced + one never-checked" rolls up to UNKNOWN, not UNAVAILABLE.
        AvailabilityStatus availability = rollUpAvailability(allListings);
        boolean mixedCurrencies = pricedListings.stream()
                        .map(p -> p.latest().getCurrency())
                        .distinct()
                        .count()
                > 1;

        // Best price counts only listings the trend engine would also count: in stock (or at least not
        // known to be out of stock), observed recently enough to still stand, and priced sanely. Sharing
        // TrendEligibility is what guarantees this row and the trend series' latest point agree.
        List<ListingWithLatestPrice> eligibleListings = pricedListings.stream()
                .filter(p -> TrendEligibility.isEligible(
                        p.latest().getTimestamp(),
                        p.latest().getAvailability(),
                        p.latest().getPrice(),
                        asOfInstant,
                        trendProperties.carryForwardDays()))
                .toList();

        if (eligibleListings.isEmpty()) {
            return emptyBestPriceResponse(product, storeCount, availability, mixedCurrencies);
        }

        List<ListingWithConvertedPrice> listingsWithConvertedPrices = eligibleListings.stream()
                .map(p -> {
                    ConvertedAmount conv = priceConverter.convert(
                            p.latest().getPrice(), p.latest().getCurrency(), displayCurrency);
                    return conv == null ? null : new ListingWithConvertedPrice(p, conv);
                })
                .filter(Objects::nonNull)
                .toList();

        if (listingsWithConvertedPrices.isEmpty()) {
            log.debug(
                    "No eligible tracked item could be converted to {} for product {}",
                    displayCurrency,
                    product.getId());
            return emptyBestPriceResponse(product, storeCount, availability, mixedCurrencies);
        }

        ListingWithConvertedPrice cheapestListing = listingsWithConvertedPrices.stream()
                .min(Comparator.<ListingWithConvertedPrice, BigDecimal>comparing(
                                ci -> ci.converted().value())
                        .thenComparing(ci -> ci.source().item().getId()))
                .orElseThrow();

        PriceRecord bestLatest = cheapestListing.source().latest();
        return new ProductSummaryResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                storeCount,
                cheapestListing.converted().value(),
                displayCurrency,
                bestLatest.getPrice(),
                bestLatest.getCurrency(),
                cheapestListing.source().item().getShopName(),
                cheapestListing.converted().asOf(),
                cheapestListing.converted().stale(),
                PriceBasis.AS_LISTED,
                availability,
                mixedCurrencies);
    }

    // Product-level availability over all trackers: any AVAILABLE wins; else any UNKNOWN (incl. a
    // never-checked tracker) beats UNAVAILABLE — we never claim "unavailable" while some store is
    // unknown; no trackers at all → UNKNOWN.
    private static AvailabilityStatus rollUpAvailability(List<ListingWithLatestPrice> allListings) {
        boolean anyTracker = false;
        boolean anyUnknown = false;
        for (ListingWithLatestPrice p : allListings) {
            anyTracker = true;
            AvailabilityStatus status = p.latest() != null ? p.latest().getAvailability() : AvailabilityStatus.UNKNOWN;
            if (status == AvailabilityStatus.AVAILABLE) {
                return AvailabilityStatus.AVAILABLE;
            }
            if (status == AvailabilityStatus.UNKNOWN) {
                anyUnknown = true;
            }
        }
        return (!anyTracker || anyUnknown) ? AvailabilityStatus.UNKNOWN : AvailabilityStatus.UNAVAILABLE;
    }

    private ProductSummaryResponse emptyBestPriceResponse(
            Product product, int storeCount, AvailabilityStatus availability, boolean mixedCurrencies) {
        return new ProductSummaryResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                storeCount,
                null,
                null,
                null,
                null,
                null,
                null,
                false,
                PriceBasis.AS_LISTED,
                availability,
                mixedCurrencies);
    }

    private TrackedItemSummary toItemSummary(TrackedItem item, PriceRecord latest) {
        return new TrackedItemSummary(
                item.getId(),
                item.getUrl(),
                item.getShopName(),
                item.getShopNameSource(),
                latest != null ? latest.getPrice() : null,
                latest != null ? latest.getCurrency() : null,
                latest != null ? latest.getAvailability() : AvailabilityStatus.UNKNOWN,
                item.getLastChecked());
    }
}
