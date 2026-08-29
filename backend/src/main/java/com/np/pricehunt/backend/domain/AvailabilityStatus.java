package com.np.pricehunt.backend.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonEnumDefaultValue;

/**
 * Whether a tracked item can be obtained ("can you get it") — not whether it is physically on a
 * shelf — so PreOrder / BackOrder / OnlineOnly all read {@code AVAILABLE}. Tri-state because a
 * scrape fails only when PRICE is missing; availability is optional metadata, and "no signal" is a
 * genuine third state ({@code UNKNOWN}) distinct from {@code UNAVAILABLE}. Collapsing the two to a
 * boolean silently fabricated "out of stock" whenever a page carried no availability signal.
 *
 * <p>Wire tokens are the uppercase names; the scraper sends lowercase, bound via Jackson's
 * case-insensitive-enums (application.properties). {@link JsonEnumDefaultValue} maps an
 * <em>unrecognized</em> token to {@code UNKNOWN} instead of throwing — but only when the mapper
 * enables the unknown-enum-default feature. Spring AI's {@code BeanOutputConverter} (Jackson 2) is
 * handed such a mapper in {@code LlmPriceExtractionService} — the one path where an off-token is
 * plausible. The global Jackson 3 mapper does not enable it (Jackson 3 moved the feature to
 * {@code EnumFeature}); the scraper only ever sends canonical tokens. {@link JsonAlias} absorbs
 * common synonyms so they resolve to a real value rather than falling through to {@code UNKNOWN}.
 * A <em>missing</em>/{@code null} field is NOT covered by either — callers coalesce that to
 * {@code UNKNOWN} before persisting.
 */
public enum AvailabilityStatus {
    @JsonAlias({"IN_STOCK", "INSTOCK", "PREORDER", "PRE_ORDER", "BACKORDER", "BACK_ORDER"})
    AVAILABLE,

    @JsonAlias({"OUT_OF_STOCK", "OUTOFSTOCK", "SOLD_OUT", "SOLDOUT"})
    UNAVAILABLE,

    @JsonEnumDefaultValue
    UNKNOWN
}
