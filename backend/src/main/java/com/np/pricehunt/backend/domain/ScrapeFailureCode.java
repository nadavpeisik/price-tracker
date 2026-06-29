package com.np.pricehunt.backend.domain;

/**
 * Stable, low-cardinality classification of WHY a scrape attempt failed (issue #131). Grafana panels
 * and corpus queries group on this; the dynamic specifics (blocked reason, model name, exception
 * message, prior price) go in the free-text {@code failure_detail} column.
 *
 * <p>Kept in lockstep with the {@code scrape_attempt_failure_code_check} CHECK constraint in V9 —
 * adding a code is a new migration (the varchar+CHECK convention, same as {@link AvailabilityStatus}
 * / {@code JobStatus}).
 */
public enum ScrapeFailureCode {
    /** Anti-bot wall — {@code ScrapeBlockedException}; no LLM input. */
    BLOCKED,
    /** Scraper returned a payload but the LLM input was below the minimum length. */
    EMPTY_INPUT,
    /** The LLM produced output the structured-output converter could not parse. */
    MALFORMED_LLM_OUTPUT,
    /** Any other extraction-time throwable (LLM transport/Spring-AI error, or an unexpected bug). */
    EXTRACTION_ERROR,
    /** Validation: extracted price was missing (null). */
    NULL_PRICE,
    /** Validation: extracted price was zero or negative. */
    PRICE_NON_POSITIVE,
    /** Validation: extracted currency was null. */
    NULL_CURRENCY,
    /** Validation: same-currency price moved beyond the configured delta. */
    DELTA_EXCEEDED
}
