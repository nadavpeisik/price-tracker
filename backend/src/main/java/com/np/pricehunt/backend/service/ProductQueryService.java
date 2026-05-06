package com.np.pricehunt.backend.service;

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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductQueryService {

    @Value("${price.history.default-window-days:90}")
    private int defaultWindowDays;

    private final ProductRepository productRepository;
    private final TrackedItemRepository trackedItemRepository;
    private final PriceRecordRepository priceRecordRepository;

    // Pairs a tracked item with its latest price record (null if no prices yet)
    private record ItemWithLatestPrice(TrackedItem item, PriceRecord latest) {}

    public Page<ProductSummaryResponse> getAllProducts(Pageable pageable) {
        // N+1 is bounded by page size — acceptable at this scale; future opt: JPQL fetch join
        return productRepository.findAll(pageable).map(product -> {
            List<ItemWithLatestPrice> pairs = fetchItemsWithLatestPrices(product);
            return toSummaryResponse(product, pairs);
        });
    }

    public ProductDetailResponse getProduct(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found"));

        List<TrackedItemSummary> summaries = fetchItemsWithLatestPrices(product).stream()
                .map(p -> toItemSummary(p.item(), p.latest()))
                .toList();

        return new ProductDetailResponse(product.getId(), product.getName(), product.getDescription(), summaries);
    }

    public PriceHistoryResponse getPriceHistory(Long productId, Long itemId, LocalDateTime from, LocalDateTime to) {
        TrackedItem item = trackedItemRepository.findById(itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found"));

        if (!item.getProduct().getId().equals(productId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Tracked item not found for this product");
        }

        LocalDateTime effectiveTo = (to != null) ? to : LocalDateTime.now();
        LocalDateTime effectiveFrom = (from != null) ? from : effectiveTo.minusDays(defaultWindowDays);

        LocalDateTime maxFrom = effectiveTo.minusYears(2);
        if (effectiveFrom.isBefore(maxFrom)) {
            log.info("Price history range clamped to 2 years for item={}", itemId);
            effectiveFrom = maxFrom;
        }

        List<PriceRecord> records = priceRecordRepository
                .findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(item, effectiveFrom, effectiveTo);

        List<PricePointResponse> history = records.stream()
                .map(r -> new PricePointResponse(
                        r.getPrice(),
                        r.getCurrency(),
                        r.isAvailable(),
                        r.getTimestamp(),
                        r.getExtractionSource().name()))
                .toList();

        return new PriceHistoryResponse(item.getId(), item.getShopName(), item.getUrl(), history);
    }

    private List<ItemWithLatestPrice> fetchItemsWithLatestPrices(Product product) {
        return trackedItemRepository.findByProduct(product).stream()
                .map(item -> new ItemWithLatestPrice(item,
                        priceRecordRepository.findFirstByTrackedItemOrderByTimestampDesc(item).orElse(null)))
                .toList();
    }

    private ProductSummaryResponse toSummaryResponse(Product product, List<ItemWithLatestPrice> pairs) {
        int storeCount = pairs.size();
        boolean anyAvailable = pairs.stream()
                .filter(p -> p.latest() != null)
                .anyMatch(p -> p.latest().isAvailable());

        List<ItemWithLatestPrice> withPrices = pairs.stream()
                .filter(p -> p.latest() != null)
                .toList();

        if (withPrices.isEmpty()) {
            return new ProductSummaryResponse(
                    product.getId(), product.getName(), product.getDescription(),
                    storeCount, null, null, null, anyAvailable, false);
        }

        Set<String> currencies = withPrices.stream()
                .map(p -> p.latest().getCurrency())
                .collect(Collectors.toSet());

        if (currencies.size() > 1) {
            return new ProductSummaryResponse(
                    product.getId(), product.getName(), product.getDescription(),
                    storeCount, null, null, null, anyAvailable, true);
        }

        ItemWithLatestPrice best = withPrices.stream()
                .min(Comparator.comparing(p -> p.latest().getPrice()))
                .orElseThrow();

        return new ProductSummaryResponse(
                product.getId(), product.getName(), product.getDescription(),
                storeCount,
                best.latest().getPrice(), best.latest().getCurrency(), best.item().getShopName(),
                anyAvailable, false);
    }

    private TrackedItemSummary toItemSummary(TrackedItem item, PriceRecord latest) {
        return new TrackedItemSummary(
                item.getId(),
                item.getUrl(),
                item.getShopName(),
                latest != null ? latest.getPrice() : null,
                latest != null ? latest.getCurrency() : null,
                latest != null && latest.isAvailable(),
                item.getLastChecked());
    }
}
