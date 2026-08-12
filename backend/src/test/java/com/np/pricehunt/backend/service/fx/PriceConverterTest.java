package com.np.pricehunt.backend.service.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.config.CurrencyProperties;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PriceConverterTest {

    @Mock
    private ExchangeRateService rateService;

    private static final LocalDate TODAY = LocalDate.of(2026, 5, 24);
    private static final Clock FIXED_CLOCK =
            Clock.fixed(TODAY.atStartOfDay(ZoneOffset.UTC).toInstant(), ZoneOffset.UTC);

    // EUR-base rates: 1 EUR = X quote
    private static final Map<String, BigDecimal> RATES = Map.of(
            "USD", new BigDecimal("1.10"),
            "ILS", new BigDecimal("4.00"),
            "GBP", new BigDecimal("0.85"));

    @Test
    void convert_sameCurrency_returnsIdentityWithNoAsOfAndNotStale() {
        PriceConverter converter = newConverter("0");

        ConvertedAmount result = converter.convert(new BigDecimal("99.99"), "USD", "USD");

        assertThat(result.value()).isEqualByComparingTo("99.99");
        assertThat(result.asOf()).isNull();
        assertThat(result.stale()).isFalse();
    }

    @Test
    void convert_sameCurrency_ignoresMarginEvenWhenConfigured() {
        // FX margin models card foreign-transaction fees — you don't pay one in your own currency.
        // No snapshotOn() stub: the identity path must short-circuit before touching the rate service.
        PriceConverter converter = newConverter("5");

        ConvertedAmount result = converter.convert(new BigDecimal("100.00"), "ILS", "ILS");

        assertThat(result.value()).isEqualByComparingTo("100.00");
    }

    @Test
    void convert_eurToUsd_appliesEurBaseDirectly() {
        PriceConverter converter = newConverter("0");
        snapshotOn(TODAY);

        ConvertedAmount result = converter.convert(new BigDecimal("100"), "EUR", "USD");

        // 100 EUR * 1.10 USD/EUR / 1 = 110.00 USD
        assertThat(result.value()).isEqualByComparingTo("110.0000");
        assertThat(result.asOf()).isEqualTo(TODAY);
        assertThat(result.stale()).isFalse();
    }

    @Test
    void convert_usdToIls_triangulatesViaEur() {
        PriceConverter converter = newConverter("0");
        snapshotOn(TODAY);

        ConvertedAmount result = converter.convert(new BigDecimal("100"), "USD", "ILS");

        // 100 USD * (4.00 ILS/EUR / 1.10 USD/EUR) ≈ 363.6364 ILS
        assertThat(result.value()).isEqualByComparingTo("363.6364");
    }

    @Test
    void convert_crossCurrency_appliesFxMargin() {
        PriceConverter converter = newConverter("2.5");
        snapshotOn(TODAY);

        ConvertedAmount result = converter.convert(new BigDecimal("100"), "USD", "ILS");

        // 363.6364 ILS * 1.025 = 372.7273 ILS
        assertThat(result.value()).isEqualByComparingTo("372.7273");
    }

    @Test
    void convert_missingFromCurrency_returnsNull() {
        PriceConverter converter = newConverter("0");
        snapshotOn(TODAY);

        ConvertedAmount result = converter.convert(new BigDecimal("100"), "ZZZ", "USD");

        assertThat(result).isNull();
    }

    @Test
    void convert_missingToCurrency_returnsNull() {
        PriceConverter converter = newConverter("0");
        snapshotOn(TODAY);

        ConvertedAmount result = converter.convert(new BigDecimal("100"), "USD", "ZZZ");

        assertThat(result).isNull();
    }

    @Test
    void convert_noSnapshotAvailable_returnsNull() {
        PriceConverter converter = newConverter("0");
        when(rateService.currentSnapshot()).thenReturn(Optional.empty());

        ConvertedAmount result = converter.convert(new BigDecimal("100"), "USD", "ILS");

        assertThat(result).isNull();
    }

    @Test
    void convert_snapshotExactlySevenDaysOld_isNotStale() {
        PriceConverter converter = newConverter("0");
        snapshotOn(TODAY.minusDays(7));

        ConvertedAmount result = converter.convert(new BigDecimal("100"), "USD", "ILS");

        assertThat(result.stale()).isFalse();
    }

    @Test
    void convert_snapshotEightDaysOld_isStale() {
        PriceConverter converter = newConverter("0");
        snapshotOn(TODAY.minusDays(8));

        ConvertedAmount result = converter.convert(new BigDecimal("100"), "USD", "ILS");

        assertThat(result.stale()).isTrue();
    }

    @Test
    void convert_lowercaseCurrencyCodes_normalized() {
        PriceConverter converter = newConverter("0");
        snapshotOn(TODAY);

        ConvertedAmount result = converter.convert(new BigDecimal("100"), "usd", "ils");

        assertThat(result).isNotNull();
        assertThat(result.value()).isEqualByComparingTo("363.6364");
    }

    @Test
    void isSupported_eur_alwaysTrueEvenWithoutSnapshot() {
        PriceConverter converter = newConverter("0");
        // No snapshot stubbed — EUR short-circuits before checking.
        assertThat(converter.isSupported("EUR")).isTrue();
    }

    @Test
    void isSupported_lowercase_normalizedBeforeLookup() {
        PriceConverter converter = newConverter("0");
        snapshotOn(TODAY);

        assertThat(converter.isSupported("usd")).isTrue();
    }

    @Test
    void isSupported_currencyInSnapshot_returnsTrue() {
        PriceConverter converter = newConverter("0");
        snapshotOn(TODAY);

        assertThat(converter.isSupported("ILS")).isTrue();
    }

    @Test
    void isSupported_currencyNotInSnapshot_returnsFalse() {
        PriceConverter converter = newConverter("0");
        snapshotOn(TODAY);

        assertThat(converter.isSupported("ZZZ")).isFalse();
    }

    @Test
    void isSupported_noSnapshot_failsOpenForGracefulColdStart() {
        // Before the first refresh completes, we can't verify support — return true so the API
        // doesn't 400 every request during the startup window. Conversion still returns null per-record.
        PriceConverter converter = newConverter("0");
        when(rateService.currentSnapshot()).thenReturn(Optional.empty());

        assertThat(converter.isSupported("ZZZ")).isTrue();
    }

    // --- Historical overload (#145): nearest-earlier per-quote rates, valued at the point's own day ---

    private static final HistoricalRateWindow WINDOW = HistoricalRateWindow.of(Map.of(
            "USD", Map.of(TODAY.minusDays(10), new BigDecimal("1.05"), TODAY, new BigDecimal("1.10")),
            "ILS", Map.of(TODAY.minusDays(10), new BigDecimal("3.90"), TODAY, new BigDecimal("4.00"))));

    @Test
    void convertAsOf_matchesSnapshotPathWhenRatesMatch() {
        PriceConverter converter = newConverter("0");

        ConvertedAmount historical = converter.convert(new BigDecimal("100"), "USD", "ILS", TODAY, WINDOW);

        // Same triangulation as the snapshot path's 363.6364 — one shared implementation.
        assertThat(historical.value()).isEqualByComparingTo("363.6364");
        assertThat(historical.asOf()).isEqualTo(TODAY);
        assertThat(historical.stale()).isFalse();
    }

    @Test
    void convertAsOf_appliesFxMargin() {
        PriceConverter converter = newConverter("2.5");

        ConvertedAmount result = converter.convert(new BigDecimal("100"), "USD", "ILS", TODAY, WINDOW);

        assertThat(result.value()).isEqualByComparingTo("372.7273");
    }

    @Test
    void convertAsOf_usesNearestEarlierRateOnAGapDay() {
        PriceConverter converter = newConverter("0");

        // Nothing published on TODAY-5; the 10-days-ago rates still value that day.
        ConvertedAmount result = converter.convert(new BigDecimal("100"), "USD", "ILS", TODAY.minusDays(5), WINDOW);

        // 100 * (3.90 / 1.05) = 371.4286
        assertThat(result.value()).isEqualByComparingTo("371.4286");
        assertThat(result.asOf()).isEqualTo(TODAY.minusDays(10));
    }

    @Test
    void convertAsOf_sameCurrency_isIdentityWithoutConsultingRates() {
        PriceConverter converter = newConverter("5");

        ConvertedAmount result =
                converter.convert(new BigDecimal("100.00"), "ILS", "ILS", TODAY, HistoricalRateWindow.empty());

        assertThat(result.value()).isEqualByComparingTo("100.00");
        assertThat(result.asOf()).isNull();
        assertThat(result.stale()).isFalse();
    }

    @Test
    void convertAsOf_unknownCurrency_returnsNull() {
        PriceConverter converter = newConverter("0");

        assertThat(converter.convert(new BigDecimal("100"), "ZZZ", "ILS", TODAY, WINDOW))
                .isNull();
        assertThat(converter.convert(new BigDecimal("100"), "USD", "ZZZ", TODAY, WINDOW))
                .isNull();
    }

    @Test
    void convertAsOf_dayBeforeAnyPublishedRate_returnsNull() {
        PriceConverter converter = newConverter("0");

        assertThat(converter.convert(new BigDecimal("100"), "USD", "ILS", TODAY.minusDays(30), WINDOW))
                .isNull();
    }

    @Test
    void convertAsOf_eurLegNeedsNoRateOfItsOwn() {
        PriceConverter converter = newConverter("0");

        ConvertedAmount result = converter.convert(new BigDecimal("100"), "EUR", "USD", TODAY, WINDOW);

        assertThat(result.value()).isEqualByComparingTo("110.0000");
        assertThat(result.asOf()).isEqualTo(TODAY);
    }

    @Test
    void convertAsOf_rateExactlySevenDaysBeforeThePointDay_isNotStale() {
        PriceConverter converter = newConverter("0");
        HistoricalRateWindow window = ratesOn(TODAY.minusDays(7));

        ConvertedAmount result = converter.convert(new BigDecimal("100"), "USD", "ILS", TODAY, window);

        assertThat(result.stale()).isFalse();
    }

    @Test
    void convertAsOf_rateEightDaysBeforeThePointDay_isStale() {
        PriceConverter converter = newConverter("0");
        HistoricalRateWindow window = ratesOn(TODAY.minusDays(8));

        ConvertedAmount result = converter.convert(new BigDecimal("100"), "USD", "ILS", TODAY, window);

        assertThat(result.stale()).isTrue();
    }

    @Test
    void convertAsOf_differingLegDates_reportsOlderDateAndIsStaleIfEitherLegIs() {
        PriceConverter converter = newConverter("0");
        HistoricalRateWindow window = HistoricalRateWindow.of(Map.of(
                "USD", Map.of(TODAY, new BigDecimal("1.10")),
                "ILS", Map.of(TODAY.minusDays(9), new BigDecimal("4.00"))));

        ConvertedAmount result = converter.convert(new BigDecimal("100"), "USD", "ILS", TODAY, window);

        assertThat(result.asOf()).isEqualTo(TODAY.minusDays(9));
        assertThat(result.stale()).isTrue();
    }

    @Test
    void convertAsOf_nonPositiveRate_returnsNullInsteadOfThrowing() {
        PriceConverter converter = newConverter("0");
        HistoricalRateWindow zeroFrom = HistoricalRateWindow.of(Map.of(
                "USD", Map.of(TODAY, BigDecimal.ZERO),
                "ILS", Map.of(TODAY, new BigDecimal("4.00"))));
        HistoricalRateWindow negativeTo = HistoricalRateWindow.of(Map.of(
                "USD", Map.of(TODAY, new BigDecimal("1.10")),
                "ILS", Map.of(TODAY, new BigDecimal("-4.00"))));

        assertThat(converter.convert(new BigDecimal("100"), "USD", "ILS", TODAY, zeroFrom))
                .isNull();
        assertThat(converter.convert(new BigDecimal("100"), "USD", "ILS", TODAY, negativeTo))
                .isNull();
    }

    @Test
    void convert_snapshotWithNonPositiveRate_returnsNullInsteadOfThrowing() {
        PriceConverter converter = newConverter("0");
        when(rateService.currentSnapshot())
                .thenReturn(Optional.of(
                        new RateSnapshot(TODAY, Map.of("USD", BigDecimal.ZERO, "ILS", new BigDecimal("4.00")))));

        assertThat(converter.convert(new BigDecimal("100"), "USD", "ILS")).isNull();
    }

    private static HistoricalRateWindow ratesOn(LocalDate asOf) {
        return HistoricalRateWindow.of(Map.of(
                "USD", Map.of(asOf, new BigDecimal("1.10")),
                "ILS", Map.of(asOf, new BigDecimal("4.00"))));
    }

    private PriceConverter newConverter(String marginPercent) {
        CurrencyProperties props = new CurrencyProperties(
                "ILS",
                new BigDecimal(marginPercent),
                new CurrencyProperties.Fx("", "", "0 30 16 * * *", Duration.ofSeconds(5), Duration.ofSeconds(10)));
        return new PriceConverter(rateService, props, FIXED_CLOCK);
    }

    private void snapshotOn(LocalDate asOf) {
        when(rateService.currentSnapshot()).thenReturn(Optional.of(new RateSnapshot(asOf, RATES)));
    }
}
