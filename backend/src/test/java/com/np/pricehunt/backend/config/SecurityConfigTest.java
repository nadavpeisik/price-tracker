package com.np.pricehunt.backend.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The boot-time guard on identity-provider configuration (#245): a misconfigured issuer or a blank
 * audience must fail the boot naming the variable, not the first bearer request hours later.
 */
class SecurityConfigTest {

    @Test
    void wellFormedIssuerAndAudience_pass() {
        assertThatCode(() ->
                        SecurityConfig.requireWellFormedIdp("https://tenant.eu.auth0.com/", List.of("pricehunt-api")))
                .doesNotThrowAnyException();
    }

    @Test
    void issuerWithoutTrailingSlash_failsFast() {
        // The derived JWKS URL would be https://tenant.eu.auth0.com.well-known/jwks.json - a
        // different host - and the issuer validator would reject every real token.
        assertThatThrownBy(() ->
                        SecurityConfig.requireWellFormedIdp("https://tenant.eu.auth0.com", List.of("pricehunt-api")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH0_ISSUER_URI");
    }

    @Test
    void unresolvedPlaceholder_failsFast() {
        // Boot's relaxed binding can pass the literal through; this check is the guarantee.
        assertThatThrownBy(() -> SecurityConfig.requireWellFormedIdp("${AUTH0_ISSUER_URI}", List.of("x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH0_ISSUER_URI");
    }

    @Test
    void hostlessIssuer_failsFast() {
        // "https:///" satisfies a prefix/suffix check and would fail only inside the decoder.
        assertThatThrownBy(() -> SecurityConfig.requireWellFormedIdp("https:///", List.of("pricehunt-api")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH0_ISSUER_URI");
    }

    @Test
    void blankAudience_failsFast() {
        // An exported-but-empty AUTH0_AUDIENCE binds to an empty list, and Boot then installs no aud
        // validator at all: tokens minted for any other API in the tenant would be accepted.
        assertThatThrownBy(() -> SecurityConfig.requireWellFormedIdp("https://tenant.eu.auth0.com/", List.of("")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH0_AUDIENCE");
        assertThatThrownBy(() -> SecurityConfig.requireWellFormedIdp("https://tenant.eu.auth0.com/", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH0_AUDIENCE");
    }
}
