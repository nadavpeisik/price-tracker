package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.config.PriceHistoryProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.*;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.util.WireMoney;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final PriceHistoryProperties historyProperties;

    private record ListingWithLatestPrice(TrackedItem item, PriceRecord latest) {}

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
                        WireMoney.decimalString(r.getPrice()),
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

    private TrackedItemSummary toItemSummary(TrackedItem item, PriceRecord latest) {
        return new TrackedItemSummary(
                item.getId(),
                item.getUrl(),
                item.getShopName(),
                item.getShopNameSource(),
                latest != null ? WireMoney.decimalString(latest.getPrice()) : null,
                latest != null ? latest.getCurrency() : null,
                latest != null ? latest.getAvailability() : AvailabilityStatus.UNKNOWN,
                item.getLastChecked());
    }
}
