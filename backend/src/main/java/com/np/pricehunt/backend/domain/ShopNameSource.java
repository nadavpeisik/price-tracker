package com.np.pricehunt.backend.domain;

import java.util.Arrays;
import java.util.List;

/**
 * Which tier produced a {@link TrackedItem}'s shop name. Distinct from {@link MappingOrigin} (where
 * a mapping <em>row</em> came from): a name resolved from any mapping row — curated or learned —
 * reads {@code MAPPING} here.
 */
public enum ShopNameSource {
    MAPPING(3), // resolved from the domain mapping table (curated seed or auto-learned)
    DETECTED(2), // weak <title> heuristic, not learned into the shared mapping
    HOST_FALLBACK(1); // prettified registrable-domain label — the floor

    private final int rank;

    ShopNameSource(int rank) {
        this.rank = rank;
    }

    /**
     * The sources a new value of {@code this} is allowed to overwrite — those whose rank is ≤ this
     * one's. Drives the atomic compare-and-set apply (a {@code null} current source — the item has
     * no name yet — is handled by the caller's query, since null outranks nothing). Keeps the
     * precedence defined only here, never duplicated into SQL.
     */
    public List<ShopNameSource> atOrBelow() {
        return Arrays.stream(values()).filter(s -> s.rank <= this.rank).toList();
    }
}
