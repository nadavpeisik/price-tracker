package com.np.pricehunt.backend.service.fx;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.config.CurrencyProperties;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class FrankfurterRateProviderTest {

    // Covers the constructor wiring (clone() + RestClientFactories.timed from fx() timeouts). The
    // RestClient is built but not called, so no network — fetchLatest() is exercised elsewhere.
    @Test
    void constructs_withTimedRequestFactory() {
        CurrencyProperties props = new CurrencyProperties(
                "ILS",
                BigDecimal.ZERO,
                new CurrencyProperties.Fx("https://primary", "https://fallback", "0 30 16 * * *", 5000, 10000));
        assertThat(new FrankfurterRateProvider(RestClient.builder(), props)).isNotNull();
    }
}
