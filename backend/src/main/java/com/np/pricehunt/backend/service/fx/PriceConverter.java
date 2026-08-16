package com.np.pricehunt.backend.service.fx;

import com.np.pricehunt.backend.config.CurrencyProperties;
import com.np.pricehunt.backend.money.MoneyPrecision;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class PriceConverter {

    private static final int INTERMEDIATE_SCALE = 12;
    static final long STALENESS_THRESHOLD_DAYS = 7;
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ExchangeRateService rateService;
    private final BigDecimal fxMarginMultiplier;
    private final Clock clock;

    public PriceConverter(ExchangeRateService rateService, CurrencyProperties properties, Clock clock) {
        this.rateService = rateService;
        this.fxMarginMultiplier = BigDecimal.ONE.add(
                properties.fxMarginPercent().divide(HUNDRED, INTERMEDIATE_SCALE, RoundingMode.HALF_UP));
        this.clock = clock;
    }

    public ConvertedAmount convert(BigDecimal amount, String fromCurrency, String toCurrency) {
        // PriceRecord.currency is still nullable in the schema (Flyway PR will tighten); return null
        // for unconvertible input rather than NPE so callers see the existing graceful-degradation contract.
        if (amount == null || fromCurrency == null || toCurrency == null) return null;
        String from = fromCurrency.toUpperCase(Locale.ROOT);
        String to = toCurrency.toUpperCase(Locale.ROOT);

        if (from.equals(to)) {
            return identity(amount);
        }

        Optional<RateSnapshot> maybe = rateService.currentSnapshot();
        if (maybe.isEmpty()) return null;
        RateSnapshot snapshot = maybe.get();

        return convertViaBaseCurrency(
                amount, rateOf(snapshot, from), rateOf(snapshot, to), snapshot.asOf(), LocalDate.now(clock));
    }

    /**
     * Converts as of a historical {@code day}, using each currency's nearest-earlier rate from a
     * pre-loaded {@link HistoricalRateWindow} rather than the live snapshot (issue #145).
     *
     * <p>Same graceful-degradation contract as the snapshot overload: null in, null out; unknown or
     * not-yet-published currency, null out. Staleness is measured against the price point's own day,
     * not today — a rate published more than a week before the day it values is stale <em>for that
     * day</em>.
     *
     * <p>With per-quote floors the two legs can carry different publication dates. The reported
     * {@code asOf} is the <b>older</b> of the two, which also makes the staleness check equivalent to
     * "stale if either leg is stale".
     */
    public ConvertedAmount convert(
            BigDecimal amount, String fromCurrency, String toCurrency, LocalDate day, HistoricalRateWindow window) {
        if (amount == null || fromCurrency == null || toCurrency == null || day == null || window == null) {
            return null;
        }
        String from = fromCurrency.toUpperCase(Locale.ROOT);
        String to = toCurrency.toUpperCase(Locale.ROOT);

        if (from.equals(to)) {
            return identity(amount);
        }

        HistoricalRateWindow.DatedRate fromRate = historicalRateOf(window, from, day);
        HistoricalRateWindow.DatedRate toRate = historicalRateOf(window, to, day);
        if (fromRate == null || toRate == null) return null;

        return convertViaBaseCurrency(
                amount, fromRate.rate(), toRate.rate(), olderOf(fromRate.asOf(), toRate.asOf()), day);
    }

    private static ConvertedAmount identity(BigDecimal amount) {
        // No rate consulted, so no as-of date and never stale — matches the row contract.
        return new ConvertedAmount(MoneyPrecision.normalize(amount), null, false);
    }

    /**
     * Shared EUR-base triangulation for both the snapshot and historical paths: {@code amount *
     * (toRate / fromRate)}, then the configured margin. Keeping one implementation is what makes the
     * dashboard row and the trend series agree by construction rather than by coincidence.
     */
    private ConvertedAmount convertViaBaseCurrency(
            BigDecimal amount, BigDecimal fromRate, BigDecimal toRate, LocalDate rateAsOf, LocalDate referenceDate) {
        // Non-positive rates can't come from the provider (it validates positivity) but could come
        // from a hand-inserted row; degrade to "unconvertible" rather than dividing by zero.
        if (fromRate == null || toRate == null || fromRate.signum() <= 0 || toRate.signum() <= 0) {
            return null;
        }

        // Carry INTERMEDIATE_SCALE digits through the division, then land on the money scale: the
        // output is an amount, so it obeys the same precision policy as a stored or rendered price.
        BigDecimal converted = MoneyPrecision.normalize(amount.multiply(toRate)
                .divide(fromRate, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
                .multiply(fxMarginMultiplier));

        boolean stale = ChronoUnit.DAYS.between(rateAsOf, referenceDate) > STALENESS_THRESHOLD_DAYS;
        return new ConvertedAmount(converted, rateAsOf, stale);
    }

    private static HistoricalRateWindow.DatedRate historicalRateOf(
            HistoricalRateWindow window, String currency, LocalDate day) {
        // The base currency has rate 1 and contributes no publication date of its own.
        return ExchangeRateService.BASE_CURRENCY.equals(currency)
                ? new HistoricalRateWindow.DatedRate(null, BigDecimal.ONE)
                : window.rateOnOrBefore(currency, day).orElse(null);
    }

    private static LocalDate olderOf(LocalDate a, LocalDate b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isBefore(b) ? a : b;
    }

    private static BigDecimal rateOf(RateSnapshot snapshot, String currency) {
        // The base currency's rate is 1 even though it is absent from the provider's `rates` map.
        return ExchangeRateService.BASE_CURRENCY.equals(currency)
                ? BigDecimal.ONE
                : snapshot.rates().get(currency);
    }
}
