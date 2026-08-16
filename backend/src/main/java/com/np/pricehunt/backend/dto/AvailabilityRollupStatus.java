package com.np.pricehunt.backend.dto;

/**
 * A product's availability across all its listings, as the dashboard renders it (issue #146).
 *
 * <p>Deliberately <b>not</b> a fourth value on {@link com.np.pricehunt.backend.domain.AvailabilityStatus}:
 * MIXED is a property of a <em>set</em> of listings, never of an observation. Putting it on the domain
 * enum would make it a legal value for {@code price_record.availability_status}, where it has no
 * meaning, and would force every extraction and validation path to handle a case that cannot occur.
 *
 * <p>The rollup is intentionally optimistic about UNKNOWN: a product is only UNAVAILABLE when every
 * listing is known to be out of stock. While any listing's state is unknown we say so rather than
 * claiming the product cannot be bought.
 */
public enum AvailabilityRollupStatus {
    /** Every listing is in stock. */
    AVAILABLE,
    /** Every listing is known to be out of stock — no unknowns. */
    UNAVAILABLE,
    /** At least one listing is in stock and at least one is not (out of stock or unknown). */
    MIXED,
    /** No listing is in stock and at least one is unknown — including "no listings tracked at all". */
    UNKNOWN
}
