package com.np.pricehunt.backend.client;

import com.np.pricehunt.backend.dto.ScrapeResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ScraperClient {

    private final RestClient restClient;

    public ScraperClient(@Value("${scraper.base-url}") String baseUrl) {
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
    }

    public String scrape(String url) {
        ScrapeResponse response = restClient.post()
                .uri("/scrape")
                .body(new ScrapeRequest(url))
                .retrieve()
                .body(ScrapeResponse.class);
        return response.innerText();
    }

    private record ScrapeRequest(String url) {}
}
