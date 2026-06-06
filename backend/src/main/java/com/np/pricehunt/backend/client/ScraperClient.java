package com.np.pricehunt.backend.client;

import com.np.pricehunt.backend.config.CorrelationIdFilter;
import com.np.pricehunt.backend.config.RestClientFactories;
import com.np.pricehunt.backend.config.ScraperClientProperties;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import java.time.Duration;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ScraperClient {

    private final RestClient restClient;

    // clone() before applying the factory so these timeouts don't leak onto the shared
    // RestClient.Builder bean used by other consumers.
    public ScraperClient(ScraperClientProperties properties, RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder
                .clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(RestClientFactories.timed(
                        Duration.ofMillis(properties.connectTimeoutMs()),
                        Duration.ofMillis(properties.readTimeoutMs())))
                .build();
    }

    public ScrapeResponse scrape(String url) {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        return restClient
                .post()
                .uri("/scrape")
                .header(
                        CorrelationIdFilter.HEADER,
                        correlationId != null
                                ? correlationId
                                : UUID.randomUUID().toString())
                .body(new ScrapeRequest(url))
                .retrieve()
                .body(ScrapeResponse.class);
    }

    private record ScrapeRequest(String url) {}
}
