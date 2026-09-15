package com.np.pricehunt.backend.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The access-token claims that carry the caller's email and whether the identity provider verified it
 * (issue #249). Auth0 puts neither in an API access token by itself; a post-login Action writes them
 * under our namespace, next to the roles claim. Properties rather than constants for the same reason the
 * roles claim is one ({@code authorities-claim-name}): another provider puts them elsewhere, and that
 * must be a configuration change.
 */
@Validated
@ConfigurationProperties("pricehunt.auth.claims")
public record IdentityClaimProperties(
        @DefaultValue("https://pricehunt.app/email") @NotBlank String email,
        @DefaultValue("https://pricehunt.app/email_verified") @NotBlank String emailVerified) {}
