package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.config.PriceTrackingProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.PriceRecord;
import com.np.pricehunt.backend.domain.ScrapeFailureCode;
import com.np.pricehunt.backend.dto.PriceInfo;
import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * Pure-logic unit tests for the extracted {@link PriceValidator} — no DB, scraper, or transaction
 * machinery (the cohesion win of pulling validation out of {@code ProductTrackingService}).
 */
class PriceValidatorTest {

    // maxDeltaPercent=200 → accepted band is [prior/3, prior*3].
    private final PriceValidator validator =
            new PriceValidator(new PriceTrackingProperties(200, Duration.ofMinutes(1)));

    private static PriceInfo info(String price, String currency) {
        return new PriceInfo(
                price == null ? null : new BigDecimal(price),
                currency,
                AvailabilityStatus.AVAILABLE,
                ExtractionSource.FULLTEXT);
    }

    private static PriceRecord previous(String price, String currency) {
        return PriceRecord.builder()
                .price(new BigDecimal(price))
                .currency(currency)
                .availability(AvailabilityStatus.AVAILABLE)
                .extractionSource(ExtractionSource.STRUCTURED)
                .build();
    }

    @Test
    void nullPrice_rejectedAsNullPrice_noDetail() {
        PriceValidator.Rejection r = validator.validate(info(null, "USD"), null);
        assertThat(r).isNotNull();
        assertThat(r.code()).isEqualTo(ScrapeFailureCode.NULL_PRICE);
        assertThat(r.detail()).isNull();
    }

    @Test
    void zeroPrice_rejectedAsNonPositive_withDetail() {
        PriceValidator.Rejection r = validator.validate(info("0", "USD"), null);
        assertThat(r.code()).isEqualTo(ScrapeFailureCode.PRICE_NON_POSITIVE);
        assertThat(r.detail()).isEqualTo("price=0");
    }

    @Test
    void negativePrice_rejectedAsNonPositive() {
        assertThat(validator.validate(info("-5.00", "USD"), null).code())
                .isEqualTo(ScrapeFailureCode.PRICE_NON_POSITIVE);
    }

    @Test
    void nullCurrency_rejectedAsNullCurrency_noDetail() {
        PriceValidator.Rejection r = validator.validate(info("10.00", null), null);
        assertThat(r.code()).isEqualTo(ScrapeFailureCode.NULL_CURRENCY);
        assertThat(r.detail()).isNull();
    }

    @Test
    void blankCurrency_rejectedAsNullCurrency() {
        assertThat(validator.validate(info("10.00", "  "), null).code()).isEqualTo(ScrapeFailureCode.NULL_CURRENCY);
    }

    @Test
    void validPrice_noPrevious_accepted() {
        assertThat(validator.validate(info("10.00", "USD"), null)).isNull();
    }

    @Test
    void currencyChanged_skipsDeltaCheck_accepted() {
        // 90 EUR vs prior 100 USD: cross-currency delta is meaningless, so the check is skipped → accepted.
        assertThat(validator.validate(info("90.00", "EUR"), previous("100.00", "USD")))
                .isNull();
    }

    @Test
    void withinUpperDelta_accepted() {
        assertThat(validator.validate(info("250.00", "USD"), previous("100.00", "USD")))
                .isNull();
    }

    @Test
    void exceedsUpperDelta_rejectedWithPriorPriceInDetail() {
        // 400 is 4x prior 100; max accepted is 3x.
        PriceValidator.Rejection r = validator.validate(info("400.00", "USD"), previous("100.00", "USD"));
        assertThat(r.code()).isEqualTo(ScrapeFailureCode.DELTA_EXCEEDED);
        assertThat(r.detail()).contains("400.00").contains("vs prior").contains("100.00");
    }

    @Test
    void belowLowerDelta_rejected() {
        // 10 is below prior/3 ≈ 33.33.
        assertThat(validator
                        .validate(info("10.00", "USD"), previous("100.00", "USD"))
                        .code())
                .isEqualTo(ScrapeFailureCode.DELTA_EXCEEDED);
    }

    @Test
    void currencyMatchIsCaseInsensitive_soDeltaStillApplies() {
        // "usd" and "USD" are the same currency → delta check runs → 400 (4x) is rejected.
        assertThat(validator
                        .validate(info("400.00", "usd"), previous("100.00", "USD"))
                        .code())
                .isEqualTo(ScrapeFailureCode.DELTA_EXCEEDED);
    }

    @Test
    void currencyWithTrailingSpace_stillMatchesSoDeltaApplies() {
        // "USD " is the same currency as "USD" — trimming both sides keeps the delta check active rather
        // than skipping it as a (bogus) currency change, so 400 (4x) is still rejected.
        assertThat(validator
                        .validate(info("400.00", "USD "), previous("100.00", "USD"))
                        .code())
                .isEqualTo(ScrapeFailureCode.DELTA_EXCEEDED);
    }
}
