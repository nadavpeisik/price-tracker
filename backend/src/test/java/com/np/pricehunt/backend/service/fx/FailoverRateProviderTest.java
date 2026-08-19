package com.np.pricehunt.backend.service.fx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class FailoverRateProviderTest {

    private static final LocalDate ASOF = LocalDate.parse("2026-08-17");

    @Mock
    private FrankfurterRateSource frankfurter;

    @Mock
    private EcbRateSource ecb;

    private FailoverRateProvider provider;

    @BeforeEach
    void setUp() {
        provider = new FailoverRateProvider(frankfurter, ecb);
    }

    @Test
    void fetchLatest_primarySucceeds_neverTouchesTheFallback() {
        RateSnapshot fresh = snapshot(Map.of("ILS", "3.4214"));
        when(frankfurter.fetchLatest()).thenReturn(fresh);

        assertThat(provider.fetchLatest()).isEqualTo(fresh);
        verifyNoInteractions(ecb);
    }

    @Test
    void fetchLatest_primaryThrows_fallsBackToEcb() {
        RateSnapshot fresh = snapshot(Map.of("ILS", "3.4214"));
        when(frankfurter.sourceName()).thenReturn("frankfurter");
        when(frankfurter.fetchLatest()).thenThrow(new IllegalStateException("Request cancelled"));
        when(ecb.fetchLatest()).thenReturn(fresh);

        assertThat(provider.fetchLatest()).isEqualTo(fresh);
    }

    // A source that answers with a well-formed but useless payload must fail OVER, not fail the
    // refresh — the check belongs after the fetch precisely so the next source still gets its turn.
    @Test
    void fetchLatest_primaryReturnsNoRates_fallsBackToEcb() {
        RateSnapshot fresh = snapshot(Map.of("ILS", "3.4214"));
        when(frankfurter.sourceName()).thenReturn("frankfurter");
        when(frankfurter.fetchLatest()).thenReturn(snapshot(Map.of()));
        when(ecb.fetchLatest()).thenReturn(fresh);

        assertThat(provider.fetchLatest()).isEqualTo(fresh);
    }

    // PriceConverter divides by the rate: zero throws and a negative inverts a price, so neither may
    // reach the cache even though the payload parsed perfectly.
    @Test
    void fetchLatest_nonPositiveRate_fallsBackToEcb() {
        RateSnapshot fresh = snapshot(Map.of("ILS", "3.4214"));
        when(frankfurter.sourceName()).thenReturn("frankfurter");
        when(frankfurter.fetchLatest()).thenReturn(snapshot(Map.of("USD", "0")));
        when(ecb.fetchLatest()).thenReturn(fresh);

        assertThat(provider.fetchLatest()).isEqualTo(fresh);
    }

    // The failure that matters is the FIRST one — why the preferred source went away. The old code
    // logged it as a bare message and rethrew only the fallback's exception, so the stack trace in the
    // log always described the wrong source.
    @Test
    void fetchLatest_allFail_reportsEveryFailureWithTheFirstAsCause() {
        IllegalStateException primaryFailure = new IllegalStateException("Request cancelled");
        IllegalStateException fallbackFailure = new IllegalStateException("ecb returned an empty response body");
        when(frankfurter.sourceName()).thenReturn("frankfurter");
        when(ecb.sourceName()).thenReturn("ecb");
        when(frankfurter.fetchLatest()).thenThrow(primaryFailure);
        when(ecb.fetchLatest()).thenThrow(fallbackFailure);

        assertThatThrownBy(provider::fetchLatest)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frankfurter")
                .hasMessageContaining("ecb")
                .hasCause(primaryFailure)
                .satisfies(e -> assertThat(e.getSuppressed()).containsExactly(fallbackFailure));
    }

    private static RateSnapshot snapshot(Map<String, String> rates) {
        Map<String, BigDecimal> parsed = new HashMap<>();
        rates.forEach((quote, rate) -> parsed.put(quote, new BigDecimal(rate)));
        return new RateSnapshot(ASOF, parsed);
    }
}
