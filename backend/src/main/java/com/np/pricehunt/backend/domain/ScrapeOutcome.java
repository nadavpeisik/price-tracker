package com.np.pricehunt.backend.domain;

/**
 * Why a {@code scrape_attempt} row exists. The table is failure-first (issue #131): we persist a row
 * only when extraction failed or a derived price was rejected — never on success. Stored as
 * varchar+CHECK (see V9), so adding a value stays a normal transactional migration.
 */
public enum ScrapeOutcome {
    /** The extraction pipeline threw before producing a usable {@code PriceInfo}. */
    EXTRACTION_FAILED,
    /** A {@code PriceInfo} was produced but failed {@code ProductTrackingService} validation. */
    VALIDATION_REJECTED
}
