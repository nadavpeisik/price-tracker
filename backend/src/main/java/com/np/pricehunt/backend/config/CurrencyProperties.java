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
    public record Fx(
            @DefaultValue("https://api.frankfurter.dev/v1/latest?base=EUR") String primaryUrl,
            @DefaultValue("https://api.exchangerate.host/latest?base=EUR") String fallbackUrl,
            @DefaultValue("0 30 16 * * *") String refreshCron,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("10s") Duration readTimeout) {}
}
