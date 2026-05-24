package com.np.pricehunt.backend.service.fx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.np.pricehunt.backend.config.CurrencyProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

@Slf4j
@Component
public class FrankfurterRateProvider {

    private final RestClient restClient;
    private final String primaryUrl;
    private final String fallbackUrl;

    public FrankfurterRateProvider(RestClient.Builder restClientBuilder, CurrencyProperties properties) {
        this.restClient = restClientBuilder.build();
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
        log.info("Fetched {} FX rates from {} (asOf={})",
                payload.rates().size(), providerName, payload.date());
        return new RateSnapshot(payload.date(), payload.rates());
    }

    // Both Frankfurter and exchangerate.host expose `date` (ISO-8601) and `rates` (currency → decimal).
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RatesPayload(LocalDate date, Map<String, BigDecimal> rates) {}
}
