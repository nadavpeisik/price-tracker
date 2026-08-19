package com.np.pricehunt.backend.config;

import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("pricehunt.currency")
public record CurrencyProperties(
        @DefaultValue("ILS") String defaultDisplay,
        @DefaultValue("0") BigDecimal fxMarginPercent,
        @DefaultValue Fx fx) {
    /**
     * One URL per provider, not a primary/fallback pair: the two speak different wire formats (JSON vs
     * the ECB's XML), so pointing either at the other's endpoint yields a parse failure, not a swap.
     * Chain order lives in {@code FailoverRateProvider}.
     */
    public record Fx(
            @DefaultValue("https://api.frankfurter.dev/v1/latest?base=EUR") String frankfurterUrl,
            @DefaultValue("https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml") String ecbUrl,
            @DefaultValue("0 30 16 * * *") String refreshCron,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout) {}
}
