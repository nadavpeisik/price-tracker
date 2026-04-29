package com.np.pricehunt.backend.controller;

import com.np.pricehunt.backend.dto.*;
import com.np.pricehunt.backend.service.ProductTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductTrackingService trackingService;

    @PostMapping
    public ResponseEntity<CreateProductResponse> createProduct(@RequestBody CreateProductRequest request) {
        CreateProductResponse response = trackingService.createProduct(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/track")
    public ResponseEntity<TrackResponse> trackUrl(
            @PathVariable Long id,
            @RequestBody TrackRequest request) {
        TrackResponse response = trackingService.trackUrl(id, request);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/{id}/tracked-items/{itemId}")
    public ResponseEntity<TrackResponse> updateTrackedItem(
            @PathVariable Long id,
            @PathVariable Long itemId,
            @RequestBody UpdateTrackedItemRequest request) {
        TrackResponse response = trackingService.updateTrackedItem(id, itemId, request);
        return ResponseEntity.ok(response);
    }
}
