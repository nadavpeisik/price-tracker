package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.auth.CurrentUser;
import com.np.pricehunt.backend.config.PriceHistoryProperties;
import com.np.pricehunt.backend.config.PriceTrendProperties;
import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.dto.*;
import com.np.pricehunt.backend.exception.NotFoundException;
import com.np.pricehunt.backend.exception.ValidationException;
import com.np.pricehunt.backend.repository.projection.DashboardListingRef;
import com.np.pricehunt.backend.repository.projection.ListingLatestObservationRow;
import com.np.pricehunt.backend.repository.projection.ProductRef;
import com.np.pricehunt.backend.service.fx.ConvertedAmount;
import com.np.pricehunt.backend.service.fx.PriceConverter;
import com.np.pricehunt.backend.service.trend.PriceTrendService;
import com.np.pricehunt.backend.service.trend.ProductTrend;
import com.np.pricehunt.backend.service.trend.TrendEligibility;
import com.np.pricehunt.backend.tenancy.UserScopedCatalog;
import com.np.pricehunt.backend.tenancy.UserScopedCatalog.ListingHistory;
import com.np.pricehunt.backend.util.WireMoney;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One user's reads of one product they track (#246): detail, the per-shop panel, a listing's history
 * and the price trend. The subject is the caller's tracked product, not the catalog's — every method
 * resolves it through the tenancy port first, so a product the caller does not track answers 404
 * exactly like one that does not exist. It must be resolved <em>first</em>, because the listings query
 * on its own answers an empty list for both "not yours" and "no listings yet", which would leak a 200
 * where the rule says 404.
 */
@Slf4j
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class TrackedProductQueryService {

    private final CurrentUser currentUser;
    private final UserScopedCatalog catalog;
    private final PriceTrendService trendService;
    private final PriceHistoryProperties historyProperties;
    private final PriceTrendProperties trendProperties;
    private final PriceConverter priceConverter;
    private final Clock clock;

    /** The panel's display order — see {@link #getListings} for the rule and why it is server-side. */
    private static final Comparator<PanelListing> LISTING_PANEL_ORDER = Comparator.comparing(
                    (PanelListing l) -> l.availability() == AvailabilityStatus.UNAVAILABLE)
            .thenComparing(PanelListing::priceConverted, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(PanelListing::trackedItemId);

    public ProductDetailResponse getProduct(Long id) {
        long userId = currentUser.userId();
        ProductRef product = requireProduct(userId, id);
        Instant now = clock.instant();

        // The detail keeps its historical meaning — the raw latest observation at any age. Only the
        // listings panel below applies the carry-forward rule.
        List<TrackedItemSummary> summaries = catalog.listingsWithLatestObservation(userId, product.id(), now).stream()
                .map(TrackedProductQueryService::toItemSummary)
                .toList();

        return new ProductDetailResponse(product.id(), product.name(), product.description(), summaries);
    }

    /**
     * The per-shop rows behind one dashboard product, FX-normalized into {@code displayCurrency} and
     * already in display order (issue #157).
     *
     * <p><b>"Current" means what the dashboard row means.</b> A listing's observation counts only if
     * it is inside {@code price.trend.carry-forward-days} ({@link TrendEligibility#isCurrent}) — the
     * rule the row's "N of M in stock" already applies — otherwise the listing shows no price and
     * {@code UNKNOWN} availability. Without this the panel would happily show a listing that went cold
     * nine days ago as the cheapest, above the very listing the row calls "best". Unlike the row's
     * headline, an out-of-stock listing keeps its price and badge here (the panel is where the user
     * sees that it is out of stock), and a non-positive hand-inserted price loses the price but keeps
     * its badge, because the row's availability count never looked at the price either.
     *
     * <p><b>Order.</b> Not out of stock first — {@code AVAILABLE} and a <em>priced</em> {@code UNKNOWN}
     * rank together, because a shop that simply never publishes stock levels is a successful scrape and
     * not a lesser offer. Then converted price ascending on exact {@link BigDecimal}s, with
     * unpriced/unconvertible listings last inside that group (which is where the {@code UNKNOWN}s that
     * came from staleness or a never-checked listing land, since they carry no price at all), ties
     * broken by listing id so equal prices are stable across requests. Sorted before formatting: the
     * wire strings would sort lexically.
     */
    public List<ProductListingResponse> getListings(Long productId, String displayCurrency) {
        long userId = currentUser.userId();
        ProductRef product = requireProduct(userId, productId);
        Instant now = clock.instant();
        int ttlDays = trendProperties.carryForwardDays();

        return catalog.listingsWithLatestObservation(userId, product.id(), now).stream()
                .map(row -> toListing(row, now, ttlDays, displayCurrency))
                .sorted(LISTING_PANEL_ORDER)
                .map(PanelListing::toResponse)
                .toList();
    }

    public PriceHistoryResponse getPriceHistory(Long productId, Long itemId, Instant from, Instant to) {
        long userId = currentUser.userId();
        Instant effectiveTo = (to != null) ? to : clock.instant();
        Instant effectiveFrom =
                (from != null) ? from : effectiveTo.minus(historyProperties.defaultWindowDays(), ChronoUnit.DAYS);

        if (effectiveFrom.isAfter(effectiveTo)) {
            throw new ValidationException("'from' timestamp cannot be after 'to' timestamp");
        }

        Instant maxFrom = effectiveTo.minus(365L * 2, ChronoUnit.DAYS);
        if (effectiveFrom.isBefore(maxFrom)) {
            log.info("Price history range clamped to 2 years for item={}", itemId);
            effectiveFrom = maxFrom;
        }

        ListingHistory history = catalog.priceHistory(userId, productId, itemId, effectiveFrom, effectiveTo)
                .orElseThrow(() -> new NotFoundException("Tracked item not found"));

        List<PricePointResponse> points = history.records().stream()
                .map(r -> new PricePointResponse(
                        WireMoney.decimalString(r.getPrice()),
                        r.getCurrency(),
                        r.getAvailability(),
                        r.getObservedAt(),
                        r.getExtractionSource().name()))
                .toList();

        return new PriceHistoryResponse(
                history.listing().id(),
                history.listing().shopName(),
                history.listing().url(),
                points);
    }

    /**
     * FX-normalized daily price series plus the 7-day delta for one product (issue #145), over the
     * listings the caller tracks it through.
     *
     * @param days sparkline window; null falls back to the configured default
     * @param displayCurrency must already be validated by the caller (the controller does this)
     */
    public PriceTrendResponse getPriceTrend(Long productId, Integer days, String displayCurrency) {
        long userId = currentUser.userId();
        ProductRef product = requireProduct(userId, productId);

        List<DashboardListingRef> listings = catalog.listings(userId, product.id());
        ProductTrend trend = trendService
                .computeProductTrends(Map.of(product.id(), listings), days, displayCurrency)
                .getOrDefault(product.id(), ProductTrend.empty());

        List<TrendPointResponse> sparkline = trend.points().stream()
                .map(point -> new TrendPointResponse(
                        point.t(),
                        WireMoney.decimalString(point.price()),
                        new BestOfferResponse(
                                point.bestOffer().trackedItemId(),
                                point.bestOffer().shopName(),
                                point.bestOffer().observedAt())))
                .toList();
        return new PriceTrendResponse(
                product.id(),
                displayCurrency,
                trend.delta7d(),
                trend.conversionAsOf(),
                trend.conversionStale(),
                sparkline);
    }

    private ProductRef requireProduct(long userId, Long productId) {
        return catalog.trackedProduct(userId, productId).orElseThrow(() -> new NotFoundException("Product not found"));
    }

    private static TrackedItemSummary toItemSummary(ListingLatestObservationRow row) {
        boolean observed = row.getObservedAt() != null;
        return new TrackedItemSummary(
                row.getTrackedItemId(),
                row.getUrl(),
                row.getShopName(),
                row.getShopNameSource(),
                observed ? WireMoney.decimalString(row.getPrice()) : null,
                observed ? row.getCurrency() : null,
                observed && row.getAvailability() != null ? row.getAvailability() : AvailabilityStatus.UNKNOWN,
                row.getLastChecked());
    }

    /**
     * One panel row before formatting — money kept as {@link BigDecimal} so the sort is exact and
     * never depends on how an amount is spelled on the wire.
     */
    private record PanelListing(
            Long trackedItemId,
            String shopName,
            String url,
            BigDecimal priceOriginal,
            String priceOriginalCurrency,
            BigDecimal priceConverted,
            String priceConvertedCurrency,
            boolean conversionStale,
            AvailabilityStatus availability,
            Instant lastChecked) {

        ProductListingResponse toResponse() {
            return new ProductListingResponse(
                    trackedItemId,
                    shopName,
                    url,
                    WireMoney.decimalString(priceOriginal),
                    priceOriginalCurrency,
                    WireMoney.decimalString(priceConverted),
                    priceConvertedCurrency,
                    conversionStale,
                    availability,
                    lastChecked);
        }
    }

    private PanelListing toListing(ListingLatestObservationRow row, Instant now, int ttlDays, String displayCurrency) {
        if (!TrendEligibility.isCurrent(row.getObservedAt(), now, ttlDays)) {
            return new PanelListing(
                    row.getTrackedItemId(),
                    row.getShopName(),
                    row.getUrl(),
                    null,
                    null,
                    null,
                    null,
                    false,
                    AvailabilityStatus.UNKNOWN,
                    row.getLastChecked());
        }

        AvailabilityStatus availability =
                row.getAvailability() != null ? row.getAvailability() : AvailabilityStatus.UNKNOWN;
        BigDecimal price = row.getPrice();
        boolean priced = price != null && price.signum() > 0;
        ConvertedAmount converted = priced ? priceConverter.convert(price, row.getCurrency(), displayCurrency) : null;

        return new PanelListing(
                row.getTrackedItemId(),
                row.getShopName(),
                row.getUrl(),
                priced ? price : null,
                priced ? row.getCurrency() : null,
                converted == null ? null : converted.value(),
                converted == null ? null : displayCurrency,
                converted != null && converted.stale(),
                availability,
                row.getLastChecked());
    }
}
