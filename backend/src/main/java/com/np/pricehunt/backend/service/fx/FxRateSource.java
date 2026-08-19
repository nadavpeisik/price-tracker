package com.np.pricehunt.backend.service.fx;

/**
 * One place daily EUR-base reference rates can be fetched from.
 *
 * <p>A source does transport and parsing only, and what it returns is a <em>candidate</em>: well-formed
 * enough to be a {@link RateSnapshot}, not yet judged fit to price anything. Deciding that is
 * {@link FailoverRateProvider}'s job, which is why this is a separate type from {@link FxRateProvider}
 * rather than the same one — the two make different promises, and while they shared an interface the
 * weaker promise was the one every consumer had to assume.
 */
public interface FxRateSource {

    /** Fetches the latest published snapshot, or throws if this source cannot supply one. */
    RateSnapshot fetchLatest();

    /** Short human name for logs and failure messages (e.g. {@code "frankfurter"}). */
    String sourceName();
}
