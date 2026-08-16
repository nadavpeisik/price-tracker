package com.np.pricehunt.backend.service.fx;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Which currencies a batch needs historical rates for — and, in the common case, that it needs none.
 *
 * <p>The interesting answer is the empty one: when every observation is already priced in the display
 * currency, {@link PriceConverter} short-circuits to identity and never consults a rate, so loading a
 * window would be two queries nothing reads. Single-currency catalogues are the norm here, which makes
 * this the difference between two wasted queries per request and none.
 */
public final class HistoricalRateRequirements {

    private HistoricalRateRequirements() {}

    /**
     * @param observedCurrencies currencies the batch's price records are denominated in; nulls are
     *     skipped, since {@code price_record.currency} predates the NOT NULL constraint
     * @param displayCurrency the conversion target, already validated by the caller
     * @return currency codes needing rates, uppercased, or empty when nothing needs converting. May
     *     include {@link ExchangeRateService#BASE_CURRENCY}, which the loader then drops — the base is
     *     rate 1 by definition and is never stored.
     */
    public static Set<String> forConversion(Collection<String> observedCurrencies, String displayCurrency) {
        String display = displayCurrency == null ? null : displayCurrency.toUpperCase(Locale.ROOT);

        Set<String> requiredCurrencies = new HashSet<>();
        for (String currency : observedCurrencies) {
            if (currency != null) {
                requiredCurrencies.add(currency.toUpperCase(Locale.ROOT));
            }
        }

        if (requiredCurrencies.stream().allMatch(currency -> currency.equals(display))) {
            return Set.of();
        }

        // Something needs converting, so both legs of the triangulation are required — every source
        // currency and the target.
        if (display != null) {
            requiredCurrencies.add(display);
        }
        return requiredCurrencies;
    }
}
