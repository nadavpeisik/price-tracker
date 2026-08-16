package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.config.PriceTrackingProperties;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.ScrapeFailureCode;
import com.np.pricehunt.backend.dto.PriceInfo;
import com.np.pricehunt.backend.money.MoneyPrecision;
import java.math.BigDecimal;
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

    /**
     * Returns a {@link Rejection} when {@code info} is unacceptable, or {@code null} when it passes.
     *
     * <p>Expects {@code info.price()} already normalized to {@link MoneyPrecision#SCALE} by the
     * persistence boundary, so the value judged here is the one the column will hold. Validating an
     * un-normalized price is what let {@code 0.00004} pass "price &gt; 0" and then store as zero.
     */
    public Rejection validate(PriceInfo info, PriceRecord previous) {
        if (info.price() == null) {
            log.warn("Validation failed: extracted price is null");
            return new Rejection(ScrapeFailureCode.NULL_PRICE, null);
        }
        if (info.price().compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("Validation failed: price is zero or negative ({})", info.price());
            return new Rejection(ScrapeFailureCode.PRICE_NON_POSITIVE, "price=" + info.price());
        }
        String currency = info.currency() == null ? null : info.currency().trim();
        if (currency == null || currency.isBlank()) {
            log.warn("Validation failed: extracted currency is null or blank");
            return new Rejection(ScrapeFailureCode.NULL_CURRENCY, null);
        }
        if (previous == null) return null;
        // A stored non-positive price is corrupt, not a baseline: max and min both collapse to zero,
        // which would reject every valid price and freeze the listing permanently. Skipping the delta
        // check lets the next good scrape become the baseline and heal it (issue #175).
        if (previous.getPrice() == null || previous.getPrice().signum() <= 0) {
            log.warn(
                    "Previous price {} is not a usable baseline - skipping delta check so the listing can recover",
                    previous.getPrice());
            return null;
        }
        // Trim both sides: a trailing-space variant ("USD ") is the SAME currency — it must not skip the
        // delta check by failing equalsIgnoreCase against the stored "USD".
        String previousCurrency =
                previous.getCurrency() == null ? null : previous.getCurrency().trim();
        if (!currency.equalsIgnoreCase(previousCurrency)) {
            log.warn("Currency changed from {} to {} — skipping delta check", previousCurrency, currency);
            return null;
        }

        // movePointLeft rather than a scaled divide: the factor is a dimensionless ratio, so it needs
        // no rounding at all, and picking an arbitrary scale here invited confusion with the money one.
        BigDecimal factor = BigDecimal.valueOf(trackingProperties.maxDeltaPercent())
                .movePointLeft(2)
                .add(BigDecimal.ONE);
        BigDecimal max =
                previous.getPrice().multiply(factor).setScale(MoneyPrecision.SCALE, MoneyPrecision.ROUNDING_MODE);
        BigDecimal min = previous.getPrice().divide(factor, MoneyPrecision.SCALE, MoneyPrecision.ROUNDING_MODE);
        if (info.price().compareTo(max) > 0 || info.price().compareTo(min) < 0) {
            log.warn(
                    "Validation failed: price {} is outside {}% delta of previous {} {}",
                    info.price(), trackingProperties.maxDeltaPercent(), previous.getPrice(), previous.getCurrency());
            return new Rejection(
                    ScrapeFailureCode.DELTA_EXCEEDED,
                    "price=%s %s vs prior %s %s"
                            .formatted(info.price(), currency, previous.getPrice(), previousCurrency));
        }
        return null;
    }
}
