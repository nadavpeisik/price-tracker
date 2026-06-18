package com.np.pricehunt.backend.service.ratelimit;

import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wiring for the rate-limit package. Owns the Caffeine {@link Ticker} here (not in the core {@code
 * ClockConfig}) so that config stays decoupled from the caching library.
 *
 * <p>Caffeine's {@code expireAfterWrite} measures elapsed time via a {@link Ticker} (monotonic
 * nanos), distinct from {@code java.time.Clock} (wall-clock). Tests inject a controllable Ticker to
 * advance time without sleeping.
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public Ticker systemTicker() {
        return Ticker.systemTicker();
    }
}
