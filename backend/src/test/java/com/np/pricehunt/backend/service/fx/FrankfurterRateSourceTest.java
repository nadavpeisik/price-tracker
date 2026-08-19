package com.np.pricehunt.backend.service.fx;

import static com.np.pricehunt.backend.service.fx.FxRateSourceFixtures.props;
import static com.np.pricehunt.backend.service.fx.FxRateSourceFixtures.respondingWith;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

class FrankfurterRateSourceTest {

    // Covers the constructor wiring (clone() + RestClientFactories.timed from fx() timeouts). The
    // RestClient is built but not called, so no network.
    @Test
    void constructs_withTimedRequestFactory() {
        assertThat(new FrankfurterRateSource(RestClient.builder(), props())).isNotNull();
    }

    @Test
    void sourceName_isStable() {
        assertThat(new FrankfurterRateSource(RestClient.builder(), props()).sourceName())
                .isEqualTo("frankfurter");
    }

    @Test
    void fetchLatest_parsesDateAndRates() {
        RestClient.Builder builder = respondingWith(
                MediaType.APPLICATION_JSON,
                "{\"amount\":1.0,\"base\":\"EUR\",\"date\":\"2026-08-17\",\"rates\":{\"ILS\":3.4214,\"USD\":1.1593}}");

        RateSnapshot snapshot = new FrankfurterRateSource(builder, props()).fetchLatest();

        assertThat(snapshot.asOf()).isEqualTo(LocalDate.parse("2026-08-17"));
        assertThat(snapshot.rates())
                .hasSize(2)
                .containsEntry("ILS", new BigDecimal("3.4214"))
                .containsEntry("USD", new BigDecimal("1.1593"));
    }

    // A base other than EUR parses perfectly and is wrong in the one way nothing downstream can see:
    // the rates would be stored as EUR-based and skew every conversion by EUR/USD.
    @Test
    void fetchLatest_nonEuroBase_throwsRatherThanRelabellingTheRates() {
        RestClient.Builder builder = respondingWith(
                MediaType.APPLICATION_JSON,
                "{\"amount\":1.0,\"base\":\"USD\",\"date\":\"2026-08-17\",\"rates\":{\"ILS\":3.4214}}");

        assertThatThrownBy(() -> new FrankfurterRateSource(builder, props()).fetchLatest())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("based on USD")
                .hasMessageContaining("expected EUR");
    }

    // The exact shape that killed the old fallback: HTTP 200, a body that deserializes without
    // complaint, and no rates anywhere in it. It has to surface as a failure the chain can act on
    // rather than as a snapshot with nothing in it.
    @Test
    void fetchLatest_errorEnvelopeUnderHttp200_throwsNamingTheSource() {
        RestClient.Builder builder = respondingWith(
                MediaType.APPLICATION_JSON,
                "{\"success\":false,\"error\":{\"code\":101,\"type\":\"missing_access_key\"}}");

        FrankfurterRateSource provider = new FrankfurterRateSource(builder, props());

        assertThatThrownBy(provider::fetchLatest)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("frankfurter");
    }
}
