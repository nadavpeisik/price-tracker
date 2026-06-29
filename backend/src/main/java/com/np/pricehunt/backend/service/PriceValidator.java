package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.config.PriceTrackingProperties;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.ScrapeFailureCode;
import com.np.pricehunt.backend.dto.PriceInfo;
import java.math.BigDecimal;
import java.math.RoundingMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Price-acceptance policy for an extracted {@link PriceInfo}, given the item's previous {@link
 * PriceRecord}: price &gt; 0, non-blank currency, and (when the currency is unchanged) the new price
 * within the configured max-delta band. Pure policy — no DB or network — so it unit-tests in isolation.
 *
 * <p>Returns a {@link Rejection} carrying the stable {@link ScrapeFailureCode} <em>and</em> the
 * human-readable {@code failure_detail} for the #131 audit, or {@code null} when the price is
 * acceptable. Bundling code + detail in one result keeps them from drifting (they used to be two
 * separate methods). Mirrors the {@code ShopNameResolver.Resolved} nested-result convention.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PriceValidator {

    private final PriceTrackingProperties trackingProperties;

    /** A rejected price: the stable {@code code} (grouped/audited on) plus dynamic {@code detail} (nullable). */
    public record Rejection(ScrapeFailureCode code, String detail) {}

    /** Returns a {@link Rejection} when {@code info} is unacceptable, or {@code null} when it passes. */
    public Rejection validate(PriceInfo info, PriceRecord previous) {
        if (info.price() == null) {
            log.warn("Validation failed: extracted price is null");
            return new Rejection(ScrapeFailureCode.NULL_PRICE, null);
        }
        if (info.price().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Validation failed: price is zero or negative ({})", info.price());
            return new Rejection(ScrapeFailureCode.PRICE_NON_POSITIVE, "price=" + info.price());
        }
        if (info.currency() == null || info.currency().isBlank()) {
            log.warn("Validation failed: extracted currency is null or blank");
            return new Rejection(ScrapeFailureCode.NULL_CURRENCY, null);
        }
        if (previous == null) return null;
        if (!info.currency().equalsIgnoreCase(previous.getCurrency())) {
            log.warn("Currency changed from {} to {} — skipping delta check", previous.getCurrency(), info.currency());
            return null;
        }

        BigDecimal factor = BigDecimal.valueOf(trackingProperties.maxDeltaPercent())
                .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP)
                .add(BigDecimal.ONE);
        BigDecimal max = previous.getPrice().multiply(factor).setScale(4, RoundingMode.HALF_UP);
        BigDecimal min = previous.getPrice().divide(factor, 4, RoundingMode.HALF_UP);
        if (info.price().compareTo(max) > 0 || info.price().compareTo(min) < 0) {
            log.warn(
                    "Validation failed: price {} is outside {}% delta of previous {} {}",
                    info.price(), trackingProperties.maxDeltaPercent(), previous.getPrice(), previous.getCurrency());
            return new Rejection(
                    ScrapeFailureCode.DELTA_EXCEEDED,
                    "price=%s %s vs prior %s %s"
                            .formatted(info.price(), info.currency(), previous.getPrice(), previous.getCurrency()));
        }
        return null;
    }
}
