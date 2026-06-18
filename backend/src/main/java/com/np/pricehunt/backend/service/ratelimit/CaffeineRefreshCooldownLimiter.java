package com.np.pricehunt.backend.service.ratelimit;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.np.pricehunt.backend.config.PriceTrackingProperties;
import org.springframework.stereotype.Component;

/**
 * In-process {@link RefreshCooldownLimiter} backed by a Caffeine cache whose entries auto-expire
 * after {@code price.tracking.min-refresh-interval}.
 *
 * <p>Replaces a hand-managed {@code ConcurrentHashMap} that grew unbounded (issue #59): Caffeine
 * frees expired entries itself, so there is no manual sweep and no leak. Single-instance only
 * (state is per-process, lost on restart) — acceptable pre-Phase-2; the DB {@code lastChecked}
 * check in {@code ProductTrackingService} is the durable, restart-surviving half of the limit.
 */
@Component
public class CaffeineRefreshCooldownLimiter implements RefreshCooldownLimiter {

    // Defensive ceiling against an ID flood (abuse / bug): expireAfterWrite bounds entries by
    // time, not by count. The legitimate working set (items refreshed within one window) is far
    // smaller; this just stops pathological growth from eating unbounded memory.
    private static final long MAX_TRACKED_COOLDOWNS = 100_000;

    // Key = TrackedItem id; value is an unused placeholder (presence == still cooling down).
    private final Cache<Long, Boolean> cooldowns;

    public CaffeineRefreshCooldownLimiter(PriceTrackingProperties props, Ticker ticker) {
        this.cooldowns = Caffeine.newBuilder()
                .expireAfterWrite(props.minRefreshInterval())
                .maximumSize(MAX_TRACKED_COOLDOWNS)
                .ticker(ticker)
                .build();
    }

    @Override
    public boolean tryAcquire(Long itemId) {
        // putIfAbsent returns the PREVIOUS value: null when the key was absent (we just claimed
        // the window -> acquired), or the existing placeholder when it is still cooling down.
        // Atomic on the backing ConcurrentMap, and a rejected attempt does not overwrite the
        // entry, so it cannot reset the expireAfterWrite clock / extend the window.
        return cooldowns.asMap().putIfAbsent(itemId, Boolean.TRUE) == null;
    }
}
