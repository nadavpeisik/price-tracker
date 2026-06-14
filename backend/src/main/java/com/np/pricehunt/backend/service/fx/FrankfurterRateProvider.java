package com.np.pricehunt.backend.service.fx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.np.pricehunt.backend.config.CurrencyProperties;
import com.np.pricehunt.backend.config.RestClientFactories;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Slf4j
@Component
public class FrankfurterRateProvider {

    private final RestClient restClient;
    private final String primaryUrl;
    private final String fallbackUrl;

    public FrankfurterRateProvider(RestClient.Builder restClientBuilder, CurrencyProperties properties) {
        // clone() before mutating: RestClient.Builder is a shared Spring bean — calling
        // requestFactory() directly on it would set these timeouts on every other consumer too.
        // HTTP_2 is fine here (unlike the cleartext scraper): these are HTTPS endpoints, so the version is
        // negotiated via ALPN with a clean HTTP/1.1 fallback — no cleartext h2c upgrade to break on.
        this.restClient = restClientBuilder
                .clone()
                .requestFactory(RestClientFactories.timed(
                        Duration.ofMillis(properties.fx().connectTimeoutMs()),
                        Duration.ofMillis(properties.fx().readTimeoutMs()),
                        HttpClient.Version.HTTP_2))
                .build();
        this.primaryUrl = properties.fx().primaryUrl();
        this.fallbackUrl = properties.fx().fallbackUrl();
    }

    public RateSnapshot fetchLatest() {
        try {
            return fetch(primaryUrl, "frankfurter");
        } catch (Exception primaryFailure) {
            log.warn("Primary FX provider failed ({}); falling back to {}", primaryFailure.getMessage(), fallbackUrl);
            return fetch(fallbackUrl, "exchangerate.host");
        }
    }

    private RateSnapshot fetch(String url, String providerName) {
        RatesPayload payload = restClient.get().uri(url).retrieve().body(RatesPayload.class);

        if (payload == null
                || payload.date() == null
                || payload.rates() == null
                || payload.rates().isEmpty()) {
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
        log.info("Fetched {} FX rates from {} (asOf={})", payload.rates().size(), providerName, payload.date());
        return new RateSnapshot(payload.date(), payload.rates());
    }

    // Both Frankfurter and exchangerate.host expose `date` (ISO-8601) and `rates` (currency → decimal).
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RatesPayload(LocalDate date, Map<String, BigDecimal> rates) {}
}
