package com.np.pricehunt.backend.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("price.validation")
public record UrlValidationProperties(
        @DefaultValue("true") boolean unsupportedSitesEnabled, List<String> unsupportedHostPatterns) {
    public UrlValidationProperties {
        unsupportedHostPatterns = unsupportedHostPatterns == null ? List.of() : List.copyOf(unsupportedHostPatterns);
    }
}
