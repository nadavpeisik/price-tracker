package com.np.pricehunt.backend.service.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Ticker;
import com.np.pricehunt.backend.config.PriceTrackingProperties;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CaffeineRefreshCooldownLimiterTest {

    private static final Duration COOLDOWN = Duration.ofMinutes(1);

    // Controllable time source: read() returns whatever nanos we set; advance() fast-forwards
    // Caffeine's expireAfterWrite clock without any real sleeping.
    private final AtomicLong nanos = new AtomicLong(0);
    private final Ticker ticker = nanos::get;

    private RefreshCooldownLimiter limiter;

    @BeforeEach
    void setUp() {
        limiter = new CaffeineRefreshCooldownLimiter(new PriceTrackingProperties(200, COOLDOWN), ticker);
    }

    private void advance(Duration d) {
        nanos.addAndGet(d.toNanos());
    }

    @Test
    void firstAttempt_acquires() {
        assertThat(limiter.tryAcquire(1L)).isTrue();
    }

    @Test
    void secondAttemptWithinWindow_isRejected() {
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(1L)).isFalse();
    }

    @Test
    void attemptAfterWindowExpires_acquiresAgain() {
        assertThat(limiter.tryAcquire(1L)).isTrue();
        advance(COOLDOWN.plusSeconds(60)); // well past the window -> entry expired (the leak fix)
        assertThat(limiter.tryAcquire(1L)).isTrue();
    }

    @Test
    void differentItems_haveIndependentCooldowns() {
        assertThat(limiter.tryAcquire(1L)).isTrue();
        assertThat(limiter.tryAcquire(2L)).isTrue(); // item 2's cooldown is unaffected by item 1
    }

    @Test
    void rejectedAttempt_doesNotExtendTheWindow() {
        assertThat(limiter.tryAcquire(1L)).isTrue(); // write at t=0, expires at t=60s
        advance(COOLDOWN.minusSeconds(1)); // t=59s
        assertThat(limiter.tryAcquire(1L)).isFalse(); // rejected; must NOT re-stamp the write time
        advance(Duration.ofSeconds(2)); // t=61s -> past the ORIGINAL 60s ttl
        // If the rejected attempt had reset the clock, the entry would live until t=119s and this
        // would be false. It is true, proving putIfAbsent left the original write time intact.
        assertThat(limiter.tryAcquire(1L)).isTrue();
    }
}
