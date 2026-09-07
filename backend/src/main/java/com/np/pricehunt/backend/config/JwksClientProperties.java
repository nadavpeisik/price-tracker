package com.np.pricehunt.backend.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Timeouts for the one outbound call the resource server makes: fetching the identity provider's JWK
 * Set (issue #245). Everything else about token validation is Boot's own configuration under
 * {@code spring.security.oauth2.resourceserver.jwt.*}. These two knobs exist because Spring Security's
 * default JWKS client uses Nimbus's 500 ms connect and read, too tight for a cold fetch to Auth0.
 *
 * <p>Named for the client it configures rather than for auth in general, alongside
 * {@code GroqClientProperties} and {@code ScraperClientProperties}.
 */
@Validated
@ConfigurationProperties("pricehunt.auth.jwks")
public record JwksClientProperties(
        // @NotNull is required: @DurationMin treats null as valid, so an explicitly-empty value would
        // slip through and fail later inside the HTTP client rather than at boot.
        @DefaultValue("5s") @NotNull @DurationMin(millis = 1) Duration connectTimeout,
        @DefaultValue("10s") @NotNull @DurationMin(millis = 1) Duration readTimeout) {}
