package com.np.pricehunt.backend.client;

import com.np.pricehunt.backend.config.CorrelationIdFilter;
import com.np.pricehunt.backend.config.RestClientFactories;
import com.np.pricehunt.backend.config.ScraperClientProperties;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import java.net.http.HttpClient;
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
        // HTTP_1_1 is required, not cosmetic: the scraper is uvicorn (HTTP/1.1-only) over cleartext
        // http://. The JDK HttpClient's default HTTP/2 would attempt an h2c upgrade that uvicorn rejects
        // with a 400, which surfaces to callers as a 502.
        this.restClient = restClientBuilder
                .clone()
                .baseUrl(properties.baseUrl())
                .requestFactory(RestClientFactories.timed(
                        properties.connectTimeout(), properties.readTimeout(), HttpClient.Version.HTTP_1_1))
                .build();
    }

    /**
     * Sends {@code url} to the scraper service. <b>SSRF precondition (#139):</b> callers MUST first run
     * the URL through {@link com.np.pricehunt.backend.validator.UrlValidator#validate(String)} — this
     * method performs no host validation. Enforced today at the shared pre-scrape chokepoint in
     * {@code ProductTrackingService.checkListingPrice} (the sole caller); making the sink self-validating is
     * a filed follow-up.
     */
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
