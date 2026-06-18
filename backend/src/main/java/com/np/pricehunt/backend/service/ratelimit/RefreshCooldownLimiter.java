package com.np.pricehunt.backend.service.ratelimit;

/**
 * Per-item cooldown for user-initiated refreshes. Stamped <em>before</em> the scrape so a failed
 * scrape (which never bumps DB {@code lastChecked}) still consumes the window.
 *
 * <p>The current implementation is in-process and single-instance ({@link
 * CaffeineRefreshCooldownLimiter}); Phase 2 (multi-instance) swaps in a shared store (Redis) behind
 * this same interface without touching callers.
 */
public interface RefreshCooldownLimiter {

    /**
     * Atomically records a refresh attempt for {@code itemId}.
     *
     * @return {@code true} if the cooldown was acquired (caller may proceed); {@code false} if the
     *     item is still within its cooldown window (caller should reject with 429).
     */
    boolean tryAcquire(Long itemId);
}
