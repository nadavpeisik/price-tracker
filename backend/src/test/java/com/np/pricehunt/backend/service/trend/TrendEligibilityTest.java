package com.np.pricehunt.backend.service.trend;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class TrendEligibilityTest {

    private static final Instant NOW = Instant.parse("2026-05-24T12:00:00Z");
    private static final int TTL = 7;

    @Test
    void isCurrent_boundsAreInclusiveOnTheOldSideAndExclusiveOnTheFuture() {
        assertThat(TrendEligibility.isCurrent(NOW, NOW, TTL)).isTrue();
        assertThat(TrendEligibility.isCurrent(NOW.minus(TTL, ChronoUnit.DAYS), NOW, TTL))
                .isTrue();
        assertThat(TrendEligibility.isCurrent(NOW.minus(TTL, ChronoUnit.DAYS).minusMillis(1), NOW, TTL))
                .isFalse();
        assertThat(TrendEligibility.isCurrent(NOW.plusMillis(1), NOW, TTL)).isFalse();
    }

    @Test
    void isCurrent_isNullSafe() {
        assertThat(TrendEligibility.isCurrent(null, NOW, TTL)).isFalse();
        assertThat(TrendEligibility.isCurrent(NOW, null, TTL)).isFalse();
    }

    @Test
    void isEligible_isCurrentPlusAvailabilityAndPositivity() {
        Instant fresh = NOW.minus(1, ChronoUnit.DAYS);
        Instant stale = NOW.minus(TTL + 1, ChronoUnit.DAYS);
        BigDecimal price = new BigDecimal("10");

        assertThat(TrendEligibility.isEligible(fresh, AvailabilityStatus.AVAILABLE, price, NOW, TTL))
                .isTrue();
        assertThat(TrendEligibility.isEligible(fresh, AvailabilityStatus.UNKNOWN, price, NOW, TTL))
                .isTrue();
        assertThat(TrendEligibility.isEligible(fresh, AvailabilityStatus.UNAVAILABLE, price, NOW, TTL))
                .isFalse();
        assertThat(TrendEligibility.isEligible(stale, AvailabilityStatus.AVAILABLE, price, NOW, TTL))
                .isFalse();
        assertThat(TrendEligibility.isEligible(fresh, AvailabilityStatus.AVAILABLE, BigDecimal.ZERO, NOW, TTL))
                .isFalse();
        assertThat(TrendEligibility.isEligible(fresh, AvailabilityStatus.AVAILABLE, null, NOW, TTL))
                .isFalse();
    }
}
