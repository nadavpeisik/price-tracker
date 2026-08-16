package com.np.pricehunt.backend.dto;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One row of the dashboard's two-cutoff query: a single listing's latest observation at one of the
 * two evaluation instants (issue #146).
 *
 * <p>An <b>interface</b> projection rather than a record because the query is native, and Spring Data
 * maps native results to interfaces by column alias — which is why the SQL quotes every alias, so
 * Postgres preserves the camelCase rather than folding it to lowercase.
 *
 * <p>{@code recordId} is carried for two reasons: it makes "latest" deterministic when two
 * observations share a timestamp, and it lets the consumer recognise the single record that both
 * windows selected and avoid feeding the calculator a duplicate.
 */
public interface CutoffObservationRow {

    Long getTrackedItemId();

    Long getRecordId();

    BigDecimal getPrice();

    String getCurrency();

    AvailabilityStatus getAvailability();

    Instant getObservedAt();

    CutoffSide getSide();
}
