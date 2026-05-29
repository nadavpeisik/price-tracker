package com.np.pricehunt.backend.service.fx;

import com.np.pricehunt.backend.config.CurrencyProperties;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Optional;

@Service
public class PriceConverter {

    static final String EUR = "EUR";
    static final int OUTPUT_SCALE = 4;
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
            return new ConvertedAmount(amount.setScale(OUTPUT_SCALE, RoundingMode.HALF_UP), null, false);
        }

        Optional<RateSnapshot> maybe = rateService.currentSnapshot();
        if (maybe.isEmpty()) return null;
        RateSnapshot snapshot = maybe.get();

        BigDecimal fromRate = rateOf(snapshot, from);
        BigDecimal toRate = rateOf(snapshot, to);
        if (fromRate == null || toRate == null) return null;

        // EUR-base triangulation: amount * (toRate / fromRate), then apply margin multiplier.
        BigDecimal converted = amount
                .multiply(toRate)
                .divide(fromRate, INTERMEDIATE_SCALE, RoundingMode.HALF_UP)
                .multiply(fxMarginMultiplier)
                .setScale(OUTPUT_SCALE, RoundingMode.HALF_UP);

        boolean stale = ChronoUnit.DAYS.between(snapshot.asOf(), LocalDate.now(clock)) > STALENESS_THRESHOLD_DAYS;
        return new ConvertedAmount(converted, snapshot.asOf(), stale);
    }

    public boolean isSupported(String currency) {
        if (currency == null) return false;
        String upper = currency.toUpperCase(Locale.ROOT);
        if (EUR.equals(upper)) return true;
        // No snapshot loaded yet (cold-start before first refresh): fail open so the API doesn't
        // 400 every request during the startup window. Conversion will return null per-record,
        // preserving the existing graceful-degradation contract.
        return rateService.currentSnapshot()
                .map(snap -> snap.rates().containsKey(upper))
                .orElse(true);
    }

    private static BigDecimal rateOf(RateSnapshot snapshot, String currency) {
        // EUR is the implicit base — its rate is 1 even when absent from the providers' `rates` map.
        return EUR.equals(currency) ? BigDecimal.ONE : snapshot.rates().get(currency);
    }
}
