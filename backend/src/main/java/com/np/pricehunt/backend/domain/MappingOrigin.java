package com.np.pricehunt.backend.domain;

/**
 * Where a {@link ShopNameMapping} row came from. Orthogonal to {@link ShopNameSource} (which tier
 * produced an item's name): both a curated and a learned row make an item read {@code MAPPING}.
 */
public enum MappingOrigin {
    CURATED, // hand-seeded, authoritative — never overwritten by learning
    LEARNED // auto-learned from a strong detection — self-heals on rebrand
}
