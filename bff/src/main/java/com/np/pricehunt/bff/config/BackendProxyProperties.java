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
 * Where the proxy forwards {@code /bff/api/**} and how long it waits. The base URL is scheme and
 * authority only: the proxied path is concatenated onto it byte-for-byte, so a path here would
 * silently prefix every backend route. A lone trailing slash is tolerated and stripped.
 *
 * <p>One read timeout for every route, sized to outlast the backend's own outbound budget on
 * {@code POST /track} (see {@code application.properties}); not a latency target.
 */
@Validated
@ConfigurationProperties("pricehunt.bff.backend")
public record BackendProxyProperties(
        String baseUrl,
        @DefaultValue("5s") @NotNull @DurationMin(millis = 1) Duration connectTimeout,
        @DefaultValue("240s") @NotNull @DurationMin(millis = 1) Duration readTimeout) {

    public BackendProxyProperties {
        baseUrl = requireSchemeAndAuthorityOnly(baseUrl);
    }

    static String requireSchemeAndAuthorityOnly(String baseUrl) {
        if (baseUrl == null || baseUrl.isBlank() || baseUrl.startsWith("${")) {
            throw new IllegalStateException("pricehunt.bff.backend.base-url must be set (was: " + baseUrl + ")");
        }
        try {
            URI uri = new URI(baseUrl);
            boolean rootOnly = uri.getRawPath() == null || uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null
                    || uri.getHost().isBlank()
                    || !rootOnly
                    || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || uri.getRawUserInfo() != null) {
                throw malformed(baseUrl);
            }
        } catch (URISyntaxException e) {
            throw malformed(baseUrl);
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private static IllegalStateException malformed(String baseUrl) {
        return new IllegalStateException("pricehunt.bff.backend.base-url must be an absolute http:// or https://"
                + " URL with a host and no path, query or fragment (e.g. http://localhost:8080), but was: " + baseUrl);
    }
}
