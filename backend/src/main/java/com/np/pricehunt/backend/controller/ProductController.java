package com.np.pricehunt.backend.controller;

import com.np.pricehunt.backend.dto.*;
import com.np.pricehunt.backend.service.ProductQueryService;
import com.np.pricehunt.backend.service.ProductTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductTrackingService trackingService;
    private final ProductQueryService queryService;

    @PostMapping
    public ResponseEntity<CreateProductResponse> createProduct(@RequestBody CreateProductRequest request) {
        CreateProductResponse response = trackingService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<Page<ProductSummaryResponse>> getAllProducts(
            @PageableDefault(size = 20, sort = {"name", "id"}) Pageable pageable) {
        return ResponseEntity.ok(queryService.getAllProducts(withStableSort(pageable)));
    }

    // Caller-provided ?sort overrides @PageableDefault entirely; append `id` so pagination stays deterministic.
    private static Pageable withStableSort(Pageable p) {
        if (p.getSort().getOrderFor("id") != null) return p;
        Sort sort = p.getSort().and(Sort.by(Sort.Order.asc("id")));
        return PageRequest.of(p.getPageNumber(), p.getPageSize(), sort);
    }

    @GetMapping("/{id}")
    public ResponseEntity<ProductDetailResponse> getProduct(@PathVariable Long id) {
        return ResponseEntity.ok(queryService.getProduct(id));
    }

    @PostMapping("/{id}/track")
    public ResponseEntity<TrackResponse> trackUrl(
            @PathVariable Long id,
            @RequestBody TrackRequest request) {
        TrackResponse response = trackingService.trackUrl(id, request);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/tracked-items/{itemId}/refresh")
    public ResponseEntity<TrackResponse> refreshTrackedItem(
            @PathVariable Long id,
            @PathVariable Long itemId) {
        return ResponseEntity.ok(trackingService.refreshTrackedItem(id, itemId));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable Long id,
            @RequestBody UpdateProductRequest request) {
        return ResponseEntity.ok(trackingService.updateProduct(id, request));
    }

    @PatchMapping("/{id}/tracked-items/{itemId}")
    public ResponseEntity<TrackResponse> updateTrackedItem(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @RequestBody UpdateTrackedItemRequest request) {
        TrackResponse response = trackingService.updateTrackedItem(id, itemId, request);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        trackingService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}/tracked-items/{itemId}")
    public ResponseEntity<Void> deleteTrackedItem(
            @PathVariable Long id,
            @PathVariable Long itemId) {
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
