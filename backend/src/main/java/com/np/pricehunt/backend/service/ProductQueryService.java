package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.config.PriceHistoryProperties;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.*;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import com.np.pricehunt.backend.service.fx.ConvertedAmount;
import com.np.pricehunt.backend.service.fx.PriceConverter;
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

    private record ItemWithLatestPrice(TrackedItem item, PriceRecord latest) {}

    private record ConvertedItem(ItemWithLatestPrice source, ConvertedAmount converted) {}

    public Page<ProductSummaryResponse> getAllProducts(Pageable pageable, String displayCurrency) {
        // displayCurrency support is validated in ProductController before this method is called.
        // N+1: O(pageSize × storesPerProduct). Acceptable at current scale; revisit with JPQL fetch join when traffic
        // grows.
        return productRepository.findAll(pageable).map(product -> {
            List<ItemWithLatestPrice> pairs = fetchItemsWithLatestPrices(product);
            return toSummaryResponse(product, pairs, displayCurrency);
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
                        r.isAvailable(),
                        r.getTimestamp(),
                        r.getExtractionSource().name()))
                .toList();

        return new PriceHistoryResponse(item.getId(), item.getShopName(), item.getUrl(), history);
    }

    private List<ItemWithLatestPrice> fetchItemsWithLatestPrices(Product product) {
        return trackedItemRepository.findByProduct(product).stream()
                .map(item -> new ItemWithLatestPrice(
                        item,
                        priceRecordRepository
                                .findFirstByTrackedItemOrderByTimestampDesc(item)
                                .orElse(null)))
                .toList();
    }

    private ProductSummaryResponse toSummaryResponse(
            Product product, List<ItemWithLatestPrice> pairs, String displayCurrency) {
        int storeCount = pairs.size();

        List<ItemWithLatestPrice> withPrices =
                pairs.stream().filter(p -> p.latest() != null).toList();

        boolean anyAvailable = withPrices.stream().anyMatch(p -> p.latest().isAvailable());
        boolean mixedCurrencies = withPrices.stream()
                        .map(p -> p.latest().getCurrency())
                        .distinct()
                        .count()
                > 1;

        if (withPrices.isEmpty()) {
            return emptyBestPriceResponse(product, storeCount, false, false);
        }

        List<ConvertedItem> convertible = withPrices.stream()
                .map(p -> {
                    ConvertedAmount conv = priceConverter.convert(
                            p.latest().getPrice(), p.latest().getCurrency(), displayCurrency);
                    return conv == null ? null : new ConvertedItem(p, conv);
                })
                .filter(Objects::nonNull)
                .toList();

        if (convertible.isEmpty()) {
            log.debug("No tracked items convertible to {} for product {}", displayCurrency, product.getId());
            return emptyBestPriceResponse(product, storeCount, anyAvailable, mixedCurrencies);
        }

        ConvertedItem best = convertible.stream()
                .min(Comparator.comparing(ci -> ci.converted().value()))
                .orElseThrow();

        PriceRecord bestLatest = best.source().latest();
        return new ProductSummaryResponse(
                product.getId(),
                product.getName(),
                product.getDescription(),
                storeCount,
                best.converted().value(),
                displayCurrency,
                bestLatest.getPrice(),
                bestLatest.getCurrency(),
                best.source().item().getShopName(),
                best.converted().asOf(),
                best.converted().stale(),
                PriceBasis.AS_LISTED,
                anyAvailable,
                mixedCurrencies);
    }

    private ProductSummaryResponse emptyBestPriceResponse(
            Product product, int storeCount, boolean anyAvailable, boolean mixedCurrencies) {
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
                anyAvailable,
                mixedCurrencies);
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
