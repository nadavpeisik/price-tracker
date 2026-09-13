package com.np.pricehunt.bff.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The boot-time checks are ours (Boot's binding is not), so they get negative tests: each malformed
 * value must fail the constructor the binder calls, with a message naming the variable to fix.
 */
class FailFastPropertiesTest {

    private static final Duration FIVE = Duration.ofSeconds(5);

    @Test
    void auth0_wellFormed_passes() {
        Auth0ClientProperties props =
                new Auth0ClientProperties("https://tenant.eu.auth0.com/", "id", "secret", "pricehunt-api", FIVE, FIVE);
        assertThat(props.issuerUri()).isEqualTo("https://tenant.eu.auth0.com/");
    }

    @Test
    void auth0_httpIssuer_fails() {
        assertThatThrownBy(() -> new Auth0ClientProperties("http://tenant.example/", "id", "secret", "aud", FIVE, FIVE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTH0_ISSUER_URI");
    }

    @Test
    void auth0_issuerWithPath_orMissingSlash_orQuery_fails() {
        for (String issuer : new String[] {
            "https://tenant.example/tenant/",
            "https://tenant.example",
            "https://tenant.example/?x=1",
            "https://user@tenant.example/",
            "https://tenant.example/#f",
            "not a url",
            null
        }) {
            assertThatThrownBy(() -> new Auth0ClientProperties(issuer, "id", "secret", "aud", FIVE, FIVE))
                    .as(String.valueOf(issuer))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void auth0_unresolvedPlaceholder_fails_namingTheVariable() {
        assertThatThrownBy(() -> new Auth0ClientProperties(
                        "https://tenant.example/", "${AUTH0_CLIENT_ID}", "secret", "aud", FIVE, FIVE))
                .hasMessageContaining("AUTH0_CLIENT_ID");
        assertThatThrownBy(() -> new Auth0ClientProperties("https://tenant.example/", "id", " ", "aud", FIVE, FIVE))
                .hasMessageContaining("AUTH0_CLIENT_SECRET");
        assertThatThrownBy(() -> new Auth0ClientProperties("https://tenant.example/", "id", "s", "", FIVE, FIVE))
                .hasMessageContaining("AUTH0_AUDIENCE");
    }

    @Test
    void backend_schemeAndAuthorityOnly_trailingSlashStripped() {
        assertThat(new BackendProxyProperties("http://localhost:8080/", FIVE, FIVE).baseUrl())
                .isEqualTo("http://localhost:8080");
        assertThat(new BackendProxyProperties("https://api.internal", FIVE, FIVE).baseUrl())
                .isEqualTo("https://api.internal");
    }

    @Test
    void backend_pathOrQueryOrBadScheme_fails() {
        for (String url : new String[] {
            "http://localhost:8080/api",
            "http://localhost:8080?x=1",
            "ftp://localhost",
            "localhost:8080",
            "${BACKEND}",
            "",
            null
        }) {
            assertThatThrownBy(() -> new BackendProxyProperties(url, FIVE, FIVE))
                    .as(String.valueOf(url))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("base-url");
        }
    }

    @Test
    void sessionPolicy_picksTheRememberMeBounds() {
        SessionPolicyProperties policy = new SessionPolicyProperties(
                Duration.ofHours(24),
                Duration.ofHours(24),
                new SessionPolicyProperties.RememberMe(Duration.ofDays(90), Duration.ofDays(30)),
                Duration.ofSeconds(20));
        assertThat(policy.absoluteTtlFor(false)).isEqualTo(Duration.ofHours(24));
        assertThat(policy.absoluteTtlFor(true)).isEqualTo(Duration.ofDays(90));
        assertThat(policy.inactivityTtlFor(true)).isEqualTo(Duration.ofDays(30));
    }

    @Test
    void sessionPolicy_inactivityPastAbsolute_fails() {
        assertThatThrownBy(() -> new SessionPolicyProperties(
                        Duration.ofHours(24),
                        Duration.ofHours(25),
                        new SessionPolicyProperties.RememberMe(Duration.ofDays(90), Duration.ofDays(30)),
                        Duration.ofSeconds(20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inactivity-ttl");
        assertThatThrownBy(() -> new SessionPolicyProperties(
                        Duration.ofHours(24),
                        Duration.ofHours(24),
                        new SessionPolicyProperties.RememberMe(Duration.ofDays(30), Duration.ofDays(90)),
                        Duration.ofSeconds(20)))
                .hasMessageContaining("remember-me");
    }
}
