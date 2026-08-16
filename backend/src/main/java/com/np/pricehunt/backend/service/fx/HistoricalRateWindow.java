package com.np.pricehunt.backend.service.fx;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Immutable, in-memory view of historical exchange rates covering one batch's date range, supporting
 * nearest-earlier ("floor") lookups without a query per price point.
 *
 * <p>Rates are indexed <b>per quote currency</b> rather than per date. ECB publishes nothing on
 * weekends and holidays, and a published date can carry only a subset of currencies; a single
 * date-keyed snapshot would then let a partial date shadow an older, still-valid rate for the
 * currencies missing from it. Per-quote floors make each currency's calendar independent.
 *
 * <p>EUR is not stored — it is {@link ExchangeRateService#BASE_CURRENCY}, with rate 1 by definition.
 */
public final class HistoricalRateWindow {

    private static final HistoricalRateWindow EMPTY = new HistoricalRateWindow(Map.of());

    private final Map<String, NavigableMap<LocalDate, BigDecimal>> byQuote;

    private HistoricalRateWindow(Map<String, NavigableMap<LocalDate, BigDecimal>> byQuote) {
        this.byQuote = byQuote;
    }

    /**
     * Builds a window from {@code quote -> (asOf -> rate)}, copying every level so later mutation of
     * the caller's maps cannot reach the window.
     */
    public static HistoricalRateWindow of(Map<String, ? extends Map<LocalDate, BigDecimal>> rates) {
        if (rates == null || rates.isEmpty()) {
            return EMPTY;
        }
        Map<String, NavigableMap<LocalDate, BigDecimal>> copy = new HashMap<>();
        rates.forEach((quote, byDate) -> {
            if (quote == null || byDate == null || byDate.isEmpty()) {
                return;
            }
            NavigableMap<LocalDate, BigDecimal> dates = new TreeMap<>();
            byDate.forEach((asOf, rate) -> {
                if (asOf != null && rate != null) {
                    dates.put(asOf, rate);
                }
            });
            if (!dates.isEmpty()) {
                copy.put(quote.toUpperCase(Locale.ROOT), Collections.unmodifiableNavigableMap(dates));
            }
        });
        return copy.isEmpty() ? EMPTY : new HistoricalRateWindow(Collections.unmodifiableMap(copy));
    }

    /** A window with no rates at all — every non-EUR lookup misses, so conversions return null. */
    public static HistoricalRateWindow empty() {
        return EMPTY;
    }

    /**
     * The newest rate for {@code quote} dated at or before {@code day}, or empty when the currency
     * has no rate that early (or is absent entirely).
     */
    public Optional<DatedRate> rateOnOrBefore(String quote, LocalDate day) {
        if (quote == null || day == null) {
            return Optional.empty();
        }
        NavigableMap<LocalDate, BigDecimal> dates = byQuote.get(quote.toUpperCase(Locale.ROOT));
        if (dates == null) {
            return Optional.empty();
        }
        Map.Entry<LocalDate, BigDecimal> entry = dates.floorEntry(day);
        return entry == null ? Optional.empty() : Optional.of(new DatedRate(entry.getKey(), entry.getValue()));
    }

    public boolean isEmpty() {
        return byQuote.isEmpty();
    }

    /** A rate together with the date it was published — the date drives staleness reporting. */
    public record DatedRate(LocalDate asOf, BigDecimal rate) {}
}
