package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.Product;
import com.np.pricehunt.backend.domain.TrackedItem;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class PriceRecordRepositoryTest {

    @Autowired private TestEntityManager em;
    @Autowired private ProductRepository productRepository;
    @Autowired private TrackedItemRepository trackedItemRepository;
    @Autowired private PriceRecordRepository priceRecordRepository;

    private TrackedItem item;
    private LocalDateTime t1;
    private LocalDateTime t2;
    private LocalDateTime t3;

    @BeforeEach
    void setUp() {
        Product product = em.persist(Product.builder().name("Laptop").build());
        item = em.persist(TrackedItem.builder()
                .url("https://amazon.com/dp/1")
                .shopName("amazon.com")
                .product(product)
                .build());

        t1 = LocalDateTime.of(2026, 1, 1, 12, 0);
        t2 = LocalDateTime.of(2026, 2, 1, 12, 0);
        t3 = LocalDateTime.of(2026, 3, 1, 12, 0);

        em.persist(record("100.00", t1));
        em.persist(record("95.00", t2));
        em.persist(record("90.00", t3));
        em.flush();
    }

    // --- Cascade delete ---

    @Test
    void deleteProduct_cascadesToTrackedItemsAndPriceRecords() {
        Product product2 = em.persist(Product.builder().name("Monitor").build());
        TrackedItem item2 = em.persist(TrackedItem.builder()
                .url("https://amazon.com/dp/2")
                .shopName("amazon.com")
                .product(product2)
                .build());
        PriceRecord rec = em.persist(record(item2, "299.99", t1));
        // Flush and clear so stale managed entities don't conflict with the delete cascade
        em.flush();
        em.clear();

        Product loaded = productRepository.findById(product2.getId()).orElseThrow();
        productRepository.delete(loaded);
        em.flush();
        em.clear();

        assertThat(trackedItemRepository.findById(item2.getId())).isEmpty();
        assertThat(priceRecordRepository.findById(rec.getId())).isEmpty();
    }

    @Test
    void deleteTrackedItem_cascadesToPriceRecords() {
        // Clear stale managed price records from @BeforeEach before the delete
        em.flush();
        em.clear();

        TrackedItem loaded = trackedItemRepository.findById(item.getId()).orElseThrow();
        trackedItemRepository.delete(loaded);
        em.flush();
        em.clear();

        assertThat(priceRecordRepository.findByTrackedItemOrderByTimestampDesc(loaded)).isEmpty();
    }

    // --- Date-range query methods ---

    @Test
    void findByTrackedItemOrderByTimestampDesc_returnsAllNewestFirst() {
        List<PriceRecord> results = priceRecordRepository.findByTrackedItemOrderByTimestampDesc(item);

        assertThat(results).hasSize(3);
        assertThat(results.get(0).getTimestamp()).isEqualTo(t3);
        assertThat(results.get(2).getTimestamp()).isEqualTo(t1);
    }

    @Test
    void findBetween_returnsOnlyRecordsInRange_orderedDesc() {
        List<PriceRecord> results = priceRecordRepository
                .findByTrackedItemAndTimestampBetweenOrderByTimestampDesc(item, t1, t2);

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getTimestamp()).isEqualTo(t2);
        assertThat(results.get(1).getTimestamp()).isEqualTo(t1);
    }

    private PriceRecord record(String price, LocalDateTime timestamp) {
        return record(item, price, timestamp);
    }

    private PriceRecord record(TrackedItem trackedItem, String price, LocalDateTime timestamp) {
        return PriceRecord.builder()
                .price(new BigDecimal(price))
                .currency("USD")
                .available(true)
                .extractionSource(ExtractionSource.STRUCTURED)
                .trackedItem(trackedItem)
                .timestamp(timestamp)
                .build();
    }
}
