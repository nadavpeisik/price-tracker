package com.np.pricehunt.backend.service.fx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.np.pricehunt.backend.config.CurrencyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;

@Slf4j
@Component
public class FrankfurterRateProvider {

    private final RestClient restClient;
    private final String primaryUrl;
    private final String fallbackUrl;

    public FrankfurterRateProvider(RestClient.Builder restClientBuilder, CurrencyProperties properties) {
        // Connect timeout lives on the HttpClient; read timeout on the factory. JdkClientHttpRequestFactory
        // exposes only setReadTimeout, so the connect side must be configured on the underlying HttpClient.
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(10));

        // clone() before mutating: RestClient.Builder is a shared Spring bean — calling
        // requestFactory() directly on it would set our 5s/10s timeouts on every other consumer too.
        this.restClient = restClientBuilder.clone()
                .requestFactory(factory)
                .build();
        this.primaryUrl = properties.fx().primaryUrl();
        this.fallbackUrl = properties.fx().fallbackUrl();
    }

    public RateSnapshot fetchLatest() {
        try {
            return fetch(primaryUrl, "frankfurter");
        } catch (Exception primaryFailure) {
            log.warn("Primary FX provider failed ({}); falling back to {}",
                    primaryFailure.getMessage(), fallbackUrl);
            return fetch(fallbackUrl, "exchangerate.host");
        }
    }

    private RateSnapshot fetch(String url, String providerName) {
        RatesPayload payload = restClient.get()
                .uri(url)
                .retrieve()
                .body(RatesPayload.class);

        if (payload == null || payload.date() == null || payload.rates() == null || payload.rates().isEmpty()) {
            throw new IllegalStateException(providerName + " returned empty FX payload");
        }
        // Validate at ingress: PriceConverter divides by fromRate, so a zero or negative rate would
        // either throw ArithmeticException or produce a negative price. Failing fast here triggers
        // the fallback URL via fetchLatest()'s catch.
        payload.rates().forEach((quote, rate) -> {
            if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
                throw new IllegalStateException(
                        providerName + " returned non-positive rate for " + quote + ": " + rate);
            }
        });
        log.info("Fetched {} FX rates from {} (asOf={})",
                payload.rates().size(), providerName, payload.date());
        return new RateSnapshot(payload.date(), payload.rates());
    }

    // Both Frankfurter and exchangerate.host expose `date` (ISO-8601) and `rates` (currency → decimal).
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RatesPayload(LocalDate date, Map<String, BigDecimal> rates) {}
}
