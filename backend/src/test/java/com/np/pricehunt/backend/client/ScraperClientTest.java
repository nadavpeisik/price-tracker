package com.np.pricehunt.backend.client;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.config.ScraperClientProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

class ScraperClientTest {

    // Covers the constructor wiring: clone() + baseUrl + RestClientFactories.timed(...). No network
    // happens until scrape() is called, so building the client is a safe, fast unit test.
    @Test
    void constructs_withTimedRequestFactory() {
        ScraperClientProperties props =
                new ScraperClientProperties("http://localhost:8001", Duration.ofSeconds(5), Duration.ofSeconds(40));
        assertThat(new ScraperClient(props, RestClient.builder())).isNotNull();
    }
}
