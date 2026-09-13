package com.np.pricehunt.bff.testsupport;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The application clock the integration suites move instead of sleeping: token expiry, the absolute
 * session bound and the inactivity clamp all read it. Starts at real time so the fake's ID-token
 * timestamps (validated on the framework's system clock) stay valid.
 */
public final class MutableClock extends Clock {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.now());

    public void advance(Duration by) {
        now.updateAndGet(current -> current.plus(by));
    }

    public void reset() {
        now.set(Instant.now());
    }

    @Override
    public ZoneId getZone() {
        return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(ZoneId zone) {
        return this;
    }

    @Override
    public Instant instant() {
        return now.get();
    }
}
