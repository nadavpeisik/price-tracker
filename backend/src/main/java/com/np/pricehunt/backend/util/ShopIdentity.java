package com.np.pricehunt.backend.util;

import java.util.Locale;

/**
 * Reduces a stored shop name to the shop it identifies, ignoring how it happens to be spelled
 * (issue #146).
 *
 * <p><b>Why this is needed.</b> The shop name is snapshotted per listing, and nothing converges the
 * siblings: {@code ShopNameResolver.normalize} trims and collapses whitespace but deliberately leaves
 * case alone, the scraper returns {@code og:site_name}/JSON-LD/title casing verbatim, the learned-
 * mapping guard compares case-sensitively, and V7 added curated mappings without backfilling existing
 * snapshots. So "Amazon" and "amazon" genuinely coexist, and without a fold the dashboard would offer
 * two filter chips for one shop — each hiding half that shop's listings.
 *
 * <p><b>The same function must run on both sides.</b> Facets are grouped by this key and the {@code
 * ?shops=} filter is matched by it, so a chip always selects exactly the listings it was derived from.
 * Folding one side only is what would orphan listings.
 *
 * <p><b>This is a display-layer fold, not a fix.</b> It leaves the stored spellings untouched, so
 * product detail, trend {@code bestOffer.shopName} and Grafana still show whatever each snapshot
 * holds. Converging them at the source is issue #166 — and that fix must produce one <em>correct</em>
 * spelling, never a lower-cased one: {@code eBay}, {@code KSP} and {@code AliExpress} have to survive.
 * Which is why nothing here ever writes the folded value back.
 */
public final class ShopIdentity {

    private ShopIdentity() {}

    /**
     * @param rawShopName a listing's stored shop name; nullable, because the column always has been
     * @return the fold key, or null when there is no shop name to speak of (null, empty or
     *     whitespace-only). A null key never appears as a facet and never matches a filter — a
     *     listing with no shop name simply cannot be selected by a chip.
     */
    public static String of(String rawShopName) {
        if (rawShopName == null) {
            return null;
        }
        String trimmed = rawShopName.trim();
        return trimmed.isEmpty() ? null : trimmed.toLowerCase(Locale.ROOT);
    }
}
