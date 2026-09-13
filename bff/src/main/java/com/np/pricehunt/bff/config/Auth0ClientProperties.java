package com.np.pricehunt.bff.config;

import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The confidential client's Auth0 credentials and the timeouts of every call the BFF makes to the
 * tenant (token endpoint, ID-token JWKS). Every Auth0 endpoint is concatenated onto the issuer
 * (see {@link Auth0ClientRegistrationConfig}), so the issuer is checked more strictly than the
 * backend's: an absolute {@code https://} URL with a host, path exactly {@code /}, and nothing else.
 * The checks run in the constructor so a misconfiguration fails the boot, not the first login; as
 * with the backend, that is also what catches an unset env var, because relaxed binding passes the
 * literal {@code ${AUTH0_CLIENT_ID}} through.
 */
@Validated
@ConfigurationProperties("pricehunt.bff.auth0")
public record Auth0ClientProperties(
        String issuerUri,
        String clientId,
        String clientSecret,
        @DefaultValue("pricehunt-api") String audience,
        @DefaultValue("5s") @NotNull @DurationMin(millis = 1) Duration connectTimeout,
        @DefaultValue("10s") @NotNull @DurationMin(millis = 1) Duration readTimeout) {

    public Auth0ClientProperties {
        requireIssuer(issuerUri);
        requireResolved(clientId, "AUTH0_CLIENT_ID");
        requireResolved(clientSecret, "AUTH0_CLIENT_SECRET");
        requireResolved(audience, "AUTH0_AUDIENCE");
    }

    static void requireIssuer(String issuerUri) {
        if (!isHttpsRootOnly(issuerUri)) {
            throw new IllegalStateException("AUTH0_ISSUER_URI must be an absolute https:// URL with a host and the"
                    + " path '/' only (the Auth0 tenant issuer, e.g. https://tenant.eu.auth0.com/), but was: "
                    + issuerUri);
        }
    }

    static void requireResolved(String value, String envVar) {
        if (value == null || value.isBlank() || value.startsWith("${")) {
            throw new IllegalStateException(envVar + " must be set (was: " + value + ")");
        }
    }

    private static boolean isHttpsRootOnly(String issuerUri) {
        if (issuerUri == null) {
            return false;
        }
        try {
            URI uri = new URI(issuerUri);
            return "https".equals(uri.getScheme())
                    && uri.getHost() != null
                    && !uri.getHost().isBlank()
                    && "/".equals(uri.getRawPath())
                    && uri.getRawQuery() == null
                    && uri.getRawFragment() == null
                    && uri.getRawUserInfo() == null;
        } catch (URISyntaxException e) {
            return false;
        }
    }
}
