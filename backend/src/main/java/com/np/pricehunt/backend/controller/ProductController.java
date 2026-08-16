package com.np.pricehunt.backend.controller;

import com.np.pricehunt.backend.dto.*;
import com.np.pricehunt.backend.service.ProductQueryService;
import com.np.pricehunt.backend.service.ProductTrackingService;
import com.np.pricehunt.backend.service.trend.PriceTrendService;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductTrackingService trackingService;
    private final ProductQueryService queryService;
    private final PriceTrendService trendService;
    private final DisplayCurrencyResolver displayCurrencyResolver;

    @PostMapping
    public ResponseEntity<CreateProductResponse> createProduct(@RequestBody CreateProductRequest request) {
        CreateProductResponse response = trackingService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    private String resolveDisplayCurrency(String requested) {
        return displayCurrencyResolver.resolve(requested);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDetailResponse> getProduct(@PathVariable Long id) {
        return ResponseEntity.ok(queryService.getProduct(id));
    }

    /**
     * FX-normalized daily price series plus the 7-day delta for one product (issue #145).
     *
     * <p>{@code days} sizes the series only; the delta is always a 7-day comparison. The window is
     * range-parameterized from the start so a product-detail chart can ask for 90/180/365 without an
     * API change.
     */
    @GetMapping("/{id}/price-trend")
    public ResponseEntity<PriceTrendResponse> getPriceTrend(
            @PathVariable Long id,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false) String displayCurrency) {
        return ResponseEntity.ok(trendService.getProductTrend(id, days, resolveDisplayCurrency(displayCurrency)));
    }

    @PostMapping("/{id}/track")
    public ResponseEntity<TrackResponse> trackUrl(@PathVariable Long id, @RequestBody TrackRequest request) {
        TrackResponse response = trackingService.trackUrl(id, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/tracked-items/{itemId}/refresh")
    public ResponseEntity<TrackResponse> refreshTrackedItem(@PathVariable Long id, @PathVariable Long itemId) {
        return ResponseEntity.ok(trackingService.refreshTrackedItem(id, itemId));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable Long id, @RequestBody UpdateProductRequest request) {
        return ResponseEntity.ok(trackingService.updateProduct(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        trackingService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/tracked-items/{itemId}")
    public ResponseEntity<Void> deleteTrackedItem(@PathVariable Long id, @PathVariable Long itemId) {
        trackingService.deleteTrackedItem(id, itemId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/tracked-items/{itemId}/price-history")
    public ResponseEntity<PriceHistoryResponse> getPriceHistory(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        return ResponseEntity.ok(queryService.getPriceHistory(id, itemId, from, to));
    }
}
