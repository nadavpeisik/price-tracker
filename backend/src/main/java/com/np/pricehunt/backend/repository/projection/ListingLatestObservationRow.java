package com.np.pricehunt.backend.repository.projection;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ShopNameSource;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One listing of a product joined to its latest observation at or before a reference instant (issue
 * #157).
 *
 * <p>An <b>interface</b> projection because the query is native (a {@code LEFT JOIN LATERAL}), and
 * Spring Data maps native results to interfaces by column alias — the SQL quotes every alias so
 * Postgres keeps the camelCase instead of folding it to lowercase.
 *
 * <p>The observation columns are <b>raw and nullable</b>: a listing that has never been scraped, or
 * has no record at or before the reference instant, still appears (that is what the outer join is
 * for) with {@code price}, {@code currency}, {@code availability} and {@code observedAt} all null.
 * The row carries no freshness judgement — {@code ProductQueryService} applies the carry-forward rule
 * for the listings panel and deliberately does not for the product detail.
 */
public interface ListingLatestObservationRow {

    Long getTrackedItemId();

    String getUrl();

    String getShopName();

    ShopNameSource getShopNameSource();

    Instant getLastChecked();

    BigDecimal getPrice();

    String getCurrency();

    AvailabilityStatus getAvailability();

    Instant getObservedAt();
}
