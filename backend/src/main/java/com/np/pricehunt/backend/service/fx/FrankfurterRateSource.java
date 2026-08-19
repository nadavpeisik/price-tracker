package com.np.pricehunt.backend.service.fx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.np.pricehunt.backend.config.CurrencyProperties;
import com.np.pricehunt.backend.config.RestClientFactories;
import java.math.BigDecimal;
import java.net.http.HttpClient;
import java.time.LocalDate;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * The preferred rate source: Frankfurter's JSON republication of the ECB reference rates.
 *
 * <p>It leads the chain over {@link EcbRateSource} because it serves JSON and dates its own payload;
 * the ECB feed behind it is the same numbers in XML. Failover is {@link FailoverRateProvider}'s job,
 * not this class's — a source that only knows how to fetch itself is a source that can be reordered,
 * dropped, or tested on its own.
 */
@Slf4j
@Component
public class FrankfurterRateSource implements FxRateSource {

    private static final String SOURCE_NAME = "frankfurter";

    private final RestClient restClient;
    private final String url;

    public FrankfurterRateSource(RestClient.Builder restClientBuilder, CurrencyProperties properties) {
        // clone() before mutating: RestClient.Builder is a shared Spring bean — calling
        // requestFactory() directly on it would set these timeouts on every other consumer too.
        // HTTP_2 is fine here (unlike the cleartext scraper): these are HTTPS endpoints, so the version is
        // negotiated via ALPN with a clean HTTP/1.1 fallback — no cleartext h2c upgrade to break on.
        this.restClient = restClientBuilder
                .clone()
                .requestFactory(RestClientFactories.timed(
                        properties.fx().connectTimeout(), properties.fx().readTimeout(), HttpClient.Version.HTTP_2))
                .build();
        this.url = properties.fx().frankfurterUrl();
    }

    @Override
    public String sourceName() {
        return SOURCE_NAME;
    }

    @Override
    public RateSnapshot fetchLatest() {
        RatesPayload payload = restClient.get().uri(url).retrieve().body(RatesPayload.class);

        // Only what it takes to build a RateSnapshot at all (its compact constructor copies the map, so a
        // null would NPE here with no source named). Emptiness and non-positive rates are the snapshot
        // policy FailoverRateProvider applies to every source alike.
        if (payload == null || payload.date() == null || payload.rates() == null) {
            throw new IllegalStateException(SOURCE_NAME + " returned empty FX payload");
        }
        // The one field of the response nothing downstream would question. Every rate we store is
        // implicitly against BASE_CURRENCY, so a URL edited to ?base=USD would not fail anywhere — it
        // would relabel USD-based rates as EUR-based and skew every converted price by EUR/USD for as
        // long as nobody noticed. Checking the base this source reports is what keeps that a failover
        // instead of a silent repricing.
        if (!ExchangeRateService.BASE_CURRENCY.equalsIgnoreCase(payload.base())) {
            throw new IllegalStateException(SOURCE_NAME + " returned rates based on " + payload.base() + ", expected "
                    + ExchangeRateService.BASE_CURRENCY);
        }
        log.info("Fetched {} FX rates from {} (asOf={})", payload.rates().size(), SOURCE_NAME, payload.date());
        return new RateSnapshot(payload.date(), payload.rates());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RatesPayload(String base, LocalDate date, Map<String, BigDecimal> rates) {}
}
