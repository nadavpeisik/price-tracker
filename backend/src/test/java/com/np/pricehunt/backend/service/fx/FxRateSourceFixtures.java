package com.np.pricehunt.backend.service.fx;

import com.np.pricehunt.backend.config.CurrencyProperties;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.web.client.RestClient;

/**
 * Canned wire responses for the rate sources.
 *
 * <p>The stub is a request <em>interceptor</em>, not a mock transport, because that is the one seam a
 * source cannot take away: each source clones the shared builder and installs its own request factory,
 * so anything bound at the factory layer is discarded before the first call. Interceptors survive both
 * the clone and the override, which keeps these tests on the real message-converter path — the exact
 * path the parsing bugs live in — without opening a socket.
 */
final class FxRateSourceFixtures {

    private FxRateSourceFixtures() {}

    /** A builder whose every request short-circuits to {@code body}, served as {@code contentType}. */
    static RestClient.Builder respondingWith(MediaType contentType, String body) {
        return RestClient.builder().requestInterceptor((request, requestBody, execution) -> {
            MockClientHttpResponse response =
                    new MockClientHttpResponse(body.getBytes(StandardCharsets.UTF_8), HttpStatus.OK);
            response.getHeaders().setContentType(contentType);
            return response;
        });
    }

    /** Properties pointing both sources at throwaway URLs — the interceptor never dials them. */
    static CurrencyProperties props() {
        return new CurrencyProperties(
                "ILS",
                BigDecimal.ZERO,
                new CurrencyProperties.Fx(
                        "https://frankfurter.example.test/v1/latest?base=EUR",
                        "https://ecb.example.test/eurofxref-daily.xml",
                        "0 30 16 * * *",
                        Duration.ofSeconds(5),
                        Duration.ofSeconds(10)));
    }
}
