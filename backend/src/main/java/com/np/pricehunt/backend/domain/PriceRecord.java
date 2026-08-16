package com.np.pricehunt.backend.domain;

import com.np.pricehunt.backend.money.MoneyPrecision;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
// `timestamp DESC` honored by Hibernate 6+ on Postgres; older providers ignore the column order silently.
// The bare `timestamp` index (V10) serves the dashboard's two-cutoff query, whose predicates are
// timestamp-only across the whole tracked set — the composite's leading column is absent there.
@Table(
        indexes = {
            @Index(name = "idx_price_record_item_timestamp", columnList = "tracked_item_id, timestamp DESC"),
            @Index(name = "idx_price_record_timestamp", columnList = "timestamp")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PriceRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(precision = 19, scale = MoneyPrecision.SCALE, nullable = false)
    private BigDecimal price;

    @Column(nullable = false)
    private String currency;

    @Column(nullable = false)
    private Instant timestamp;

    @Enumerated(EnumType.STRING)
    @Column(name = "availability_status", length = 32, nullable = false)
    private AvailabilityStatus availability;

    @Enumerated(EnumType.STRING)
    private ExtractionSource extractionSource;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tracked_item_id", nullable = false)
    private TrackedItem trackedItem;

    @PrePersist
    protected void onCreate() {
        if (this.timestamp == null) {
            this.timestamp = Instant.now();
        }
    }
}
