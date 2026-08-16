package com.np.pricehunt.backend.service.fx;

import com.np.pricehunt.backend.domain.ExchangeRate;
import com.np.pricehunt.backend.repository.ExchangeRateRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Loads the historical rates a batch needs into a single in-memory {@link HistoricalRateWindow} (issue #145).
 *
 * <p>One load per batch, not one query per price point: a bounded range fetch restricted to the
 * currencies actually present in the batch, plus one anchor query per currency for the newest rate
 * at or before the window start (the window's first days would otherwise be unconvertible whenever
 * the range begins on a weekend, a holiday, or any gap).
 *
 * <p>The anchor queries run in a loop, bounded by the number of <em>distinct foreign currencies</em>
 * in the batch — typically one to three per dashboard page, not per product or per listing.
 *
 * <p>Deliberately separate from {@link ExchangeRateService}, which stays a single-latest-snapshot
 * cache serving the live conversion path.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HistoricalRateWindowLoader {

    private final ExchangeRateRepository repository;

    /**
     * @param quotes currency codes needing conversion; EUR is ignored (implicit base) and an empty
     *     result set short-circuits without touching the database.
     */
    public HistoricalRateWindow load(LocalDate earliestDay, LocalDate latestDay, Set<String> quotes) {
        if (earliestDay == null || latestDay == null || quotes == null || earliestDay.isAfter(latestDay)) {
            return HistoricalRateWindow.empty();
        }

        Set<String> requiredQuotes = new LinkedHashSet<>();
        for (String quote : quotes) {
            if (quote == null || quote.isBlank()) {
                continue;
            }
            String normalizedQuote = quote.toUpperCase(Locale.ROOT);
            if (!ExchangeRateService.BASE_CURRENCY.equals(normalizedQuote)) {
                requiredQuotes.add(normalizedQuote);
            }
        }
        // Everything converts EUR-to-EUR (or nothing needs converting at all): never ask Hibernate to
        // bind an empty IN list.
        if (requiredQuotes.isEmpty()) {
            return HistoricalRateWindow.empty();
        }

        Map<String, Map<LocalDate, BigDecimal>> ratesByQuote = new HashMap<>();

        // Anchor first: the newest rate at or before the window start, per currency.
        for (String quote : requiredQuotes) {
            repository
                    .findTopByQuoteAndAsOfLessThanEqualOrderByAsOfDesc(quote, earliestDay)
                    .ifPresent(anchor -> ratesByQuote
                            .computeIfAbsent(quote, k -> new HashMap<>())
                            .put(anchor.getAsOf(), anchor.getRate()));
        }

        // Then one bounded range fetch. It runs whether or not anchors were found: a currency with no
        // anchor is simply unconvertible for the days before its first in-range rate.
        List<ExchangeRate> ratesInWindow =
                repository.findByQuoteInAndAsOfBetweenOrderByAsOfAsc(requiredQuotes, earliestDay, latestDay);
        for (ExchangeRate rate : ratesInWindow) {
            ratesByQuote
                    .computeIfAbsent(rate.getQuote().toUpperCase(Locale.ROOT), k -> new HashMap<>())
                    .put(rate.getAsOf(), rate.getRate());
        }

        if (ratesByQuote.isEmpty()) {
            log.debug("No historical rates found for {} between {} and {}", requiredQuotes, earliestDay, latestDay);
        }
        return HistoricalRateWindow.of(ratesByQuote);
    }
}
