package com.np.pricehunt.backend.controller;

import com.np.pricehunt.backend.service.ProductTrackingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductTrackingService trackingService;

    @PostMapping("/track")
    public ResponseEntity<String> trackProduct(@RequestBody Map<String, String> request) {
        String url = request.get("url");
        if (url == null) return ResponseEntity.badRequest().body("URL is required");

        String result = trackingService.trackNewUrl(url);
        return ResponseEntity.ok(result);
    }
}

