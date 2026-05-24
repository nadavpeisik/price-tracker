package com.np.pricehunt.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.math.BigDecimal;

@ConfigurationProperties("pricehunt.currency")
public record CurrencyProperties(
        @DefaultValue("ILS") String defaultDisplay,
        @DefaultValue("0") BigDecimal fxMarginPercent,
        @DefaultValue Fx fx
) {
    public record Fx(
            @DefaultValue("https://api.frankfurter.dev/v1/latest?base=EUR") String primaryUrl,
            @DefaultValue("https://api.exchangerate.host/latest?base=EUR") String fallbackUrl,
            @DefaultValue("0 30 16 * * *") String refreshCron
    ) {}
}
