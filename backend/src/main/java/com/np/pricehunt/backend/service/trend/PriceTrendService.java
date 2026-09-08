package com.np.pricehunt.backend.service.trend;

import com.np.pricehunt.backend.config.PriceTrendProperties;
import com.np.pricehunt.backend.exception.ValidationException;
import com.np.pricehunt.backend.repository.PriceRecordRepository;
import com.np.pricehunt.backend.repository.projection.DashboardListingRef;
import com.np.pricehunt.backend.repository.projection.TrendRecordView;
import com.np.pricehunt.backend.service.fx.HistoricalRateRequirements;
import com.np.pricehunt.backend.service.fx.HistoricalRateWindow;
import com.np.pricehunt.backend.service.fx.HistoricalRateWindowLoader;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads what the price-trend engine needs and hands it to {@link PriceTrendCalculator} (issue #145).
 *
 * <p>Deliberately batch-first: {@link #computeProductTrends} takes many products and issues <b>one</b>
 * price query and <b>one</b> rate-window load for the whole set, so the dashboard query endpoint (#146)
 * can call it per page without an N+1. The single-product endpoint is just a batch of one.
 *
 * <p>An engine, not a user-facing service (#246): it takes listing refs the tenancy port already
 * resolved and never asks who the caller is, so the id-bounded price query below is scoped by
 * construction — the ids entering it came through membership.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class PriceTrendService {

    private final PriceRecordRepository priceRecordRepository;
    private final HistoricalRateWindowLoader rateWindowLoader;
    private final PriceTrendCalculator calculator;
    private final PriceTrendProperties trendProperties;
    private final Clock clock;

    /**
     * Computes trends for every product in {@code listingsByProductId}.
     *
     * <p>The {@code days} resolution lives here rather than in the endpoint because this is the
     * reusable entry point: every caller gets the same bounds without repeating them.
     *
     * <p>Boundedness is the caller's responsibility and is satisfied by paging: a dashboard page of
     * ~100 products over the default 30-day window projects tens of thousands of rows at worst, far
     * under any bind-parameter or memory limit. The 730-day ceiling exists for the single-product
     * detail view, not for batch use.
     *
     * @param displayCurrency must already be validated by the caller
     */
    public Map<Long, ProductTrend> computeProductTrends(
            Map<Long, List<DashboardListingRef>> listingsByProductId, Integer days, String displayCurrency) {
        return computeProductTrendsAsOf(listingsByProductId, days, displayCurrency, clock.instant());
    }

    /**
     * As above, but evaluated at an instant the <em>caller</em> owns rather than the clock.
     *
     * <p>For a request that computes in more than one pass — the dashboard runs a whole-set pass and
     * then the page's sparklines — reading the clock twice lets the passes straddle a second, or at a
     * UTC midnight a day, and report a headline price and a series that disagree about when "now" was.
     *
     * @param asOfInstant evaluation instant; callers with a single pass want {@link
     *     #computeProductTrends(Map, Integer, String)} instead
     */
    public Map<Long, ProductTrend> computeProductTrendsAsOf(
            Map<Long, List<DashboardListingRef>> listingsByProductId,
            Integer days,
            String displayCurrency,
            Instant asOfInstant) {

        int trendWindowDays = resolveTrendWindowDays(days);
        LocalDate asOfDay = LocalDate.ofInstant(asOfInstant, ZoneOffset.UTC);
        LocalDate trendWindowStartDay = asOfDay.minusDays(trendWindowDays - 1L);

        // The delta baseline sits at now−7d regardless of the requested window, and carry-forward can
        // reach a further carryForwardDays back, so the fetch floor is independent of `days`.
        Instant deltaBaselineInstant = asOfInstant.minus(PriceTrendCalculator.DELTA_WINDOW_DAYS, ChronoUnit.DAYS);
        Instant trendWindowStartInstant =
                trendWindowStartDay.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant recordFetchStartInstant = (trendWindowStartInstant.isBefore(deltaBaselineInstant)
                        ? trendWindowStartInstant
                        : deltaBaselineInstant)
                .minus(trendProperties.carryForwardDays(), ChronoUnit.DAYS);

        Set<Long> listingIds = listingsByProductId.values().stream()
                .filter(Objects::nonNull)
                .flatMap(List::stream)
                .map(DashboardListingRef::trackedItemId)
                .collect(Collectors.toSet());

        Map<Long, List<TrendRecordView>> recordsByListingId =
                fetchTrendRecordsByListingId(listingIds, recordFetchStartInstant, asOfInstant);

        LocalDate rateWindowStartDay =
                trendWindowStartDay.isBefore(LocalDate.ofInstant(deltaBaselineInstant, ZoneOffset.UTC))
                        ? trendWindowStartDay
                        : LocalDate.ofInstant(deltaBaselineInstant, ZoneOffset.UTC);
        List<String> observedCurrencies = recordsByListingId.values().stream()
                .flatMap(List::stream)
                .map(TrendRecordView::currency)
                .toList();
        HistoricalRateWindow rateWindow = rateWindowLoader.load(
                rateWindowStartDay,
                asOfDay,
                HistoricalRateRequirements.forConversion(observedCurrencies, displayCurrency));

        Map<Long, ProductTrend> trendsByProductId = new LinkedHashMap<>();
        listingsByProductId.forEach((productId, listings) -> {
            List<ListingWindow> listingWindows = (listings == null ? List.<DashboardListingRef>of() : listings)
                    .stream()
                            .map(listing -> new ListingWindow(
                                    listing.trackedItemId(),
                                    listing.shopName(),
                                    recordsByListingId.getOrDefault(listing.trackedItemId(), List.of())))
                            .toList();
            trendsByProductId.put(
                    productId,
                    calculator.compute(
                            listingWindows,
                            trendWindowStartDay,
                            asOfInstant,
                            displayCurrency,
                            rateWindow,
                            trendProperties.carryForwardDays()));
        });
        return trendsByProductId;
    }

    private Map<Long, List<TrendRecordView>> fetchTrendRecordsByListingId(
            Set<Long> listingIds, Instant from, Instant to) {
        if (listingIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<TrendRecordView>> byListingId = new HashMap<>();
        for (TrendRecordView observation : priceRecordRepository.findTrendRecords(listingIds, from, to)) {
            byListingId
                    .computeIfAbsent(observation.trackedItemId(), k -> new ArrayList<>())
                    .add(observation);
        }
        return byListingId;
    }

    /** Applies the default when unset, rejects nonsense, and clamps to the configured ceiling. */
    private int resolveTrendWindowDays(Integer days) {
        if (days == null) {
            return trendProperties.defaultWindowDays();
        }
        if (days < 1) {
            throw new ValidationException("days must be >= 1");
        }
        if (days > trendProperties.maxWindowDays()) {
            log.info("Price-trend window clamped from {} to {} days", days, trendProperties.maxWindowDays());
            return trendProperties.maxWindowDays();
        }
        return days;
    }
}
