package com.np.pricehunt.backend.service.trend;

import java.time.Instant;

/**
 * Which listing supplied a day's best price, and when that price was actually observed.
 *
 * <p>{@code observedAt} is the winning record's own scrape timestamp, which on a carried-forward day
 * is older than the day itself. That is the honest claim: not "this shop sold it at this price on
 * that day", but "this shop's last known price was the best available for that day" — enough for a
 * tooltip to say "last checked 3 days ago".
 *
 * <p>{@code trackedItemId} rather than shop name alone is the stable identity: a shop can have more
 * than one tracked listing, and its display name can be re-resolved over time.
 */
public record BestOffer(long trackedItemId, String shopName, Instant observedAt) {}
