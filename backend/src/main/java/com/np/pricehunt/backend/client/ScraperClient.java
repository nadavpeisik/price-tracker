package com.np.pricehunt.backend.client;

import com.np.pricehunt.backend.config.CorrelationIdFilter;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ScraperClient {

    private final RestClient restClient;

    public ScraperClient(@Value("${scraper.base-url}") String baseUrl, RestClient.Builder restClientBuilder) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
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
