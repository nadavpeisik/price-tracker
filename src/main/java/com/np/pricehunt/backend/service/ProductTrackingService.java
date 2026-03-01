package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import com.np.pricehunt.backend.dto.PriceInfo;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.ProductRepository;
import com.np.pricehunt.backend.repository.TrackedItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class ProductTrackingService {

    private final ProductRepository productRepository;
    private final TrackedItemRepository trackedItemRepository;
    private final PriceRecordRepository priceRecordRepository;
    private final PriceExtractionService extractionService;

    @Transactional
    public String trackNewUrl(String url) {
        // 1. Mock Scraper Output
        String mockHtml = "<html><body>Price: $199.99 USD</body></html>";

        // 2. AI Extraction - Now uses the DTO correctly
        PriceInfo info = extractionService.extractPrice(mockHtml);

        // 3. Logic: Check if product exists, or create new
        Product product = productRepository.findByNameIgnoreCase("Super Gadget")
                .orElseGet(() -> productRepository.save(Product.builder()
                        .name("Super Gadget")
                        .description("AI Extracted Product")
                        .build()));

        // 4. Create the Shop Link (TrackedItem)
        TrackedItem item = trackedItemRepository.findByUrl(url)
                .orElseGet(() -> trackedItemRepository.save(TrackedItem.builder()
                        .url(url)
                        .shopName("Auto-Detected Shop")
                        .product(product)
                        .build()));

        // 5. Save the History (PriceRecord)
        priceRecordRepository.save(PriceRecord.builder()
                .price(info.price())
                .available(info.available())
                .timestamp(LocalDateTime.now())
                .trackedItem(item)
                .build());

        return "Successfully tracking " + product.getName() + " at " + info.price();
    }
}