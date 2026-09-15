package com.np.pricehunt.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.np.pricehunt.backend.config.IdentityClaimProperties;
import com.np.pricehunt.backend.exception.ForbiddenException;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The account off the enriched principal (#245, #248): the id and preference come from the token the
 * converter built, an empty principal is the 403 kind, and reaching this with no request at all is a
 * wiring bug, not a client error. No repository is involved at any point. {@code identity()} (#249) is
 * the one accessor that answers for an unadmitted caller, straight off the token's claims.
 */
class CurrentUserTest {

    private static final String EMAIL_CLAIM = "https://pricehunt.app/email";
    private static final String EMAIL_VERIFIED_CLAIM = "https://pricehunt.app/email_verified";

    private final CurrentUser currentUser =
            new CurrentUser(new IdentityClaimProperties(EMAIL_CLAIM, EMAIL_VERIFIED_CLAIM));

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void signedInAs(Optional<AdmittedUser> admitted) {
        signedInAs(admitted, Map.of("iss", "https://issuer.invalid/", "sub", "auth0|someone"));
    }

    private static void signedInAs(Optional<AdmittedUser> admitted, Map<String, Object> claims) {
        Jwt jwt = new Jwt("token", Instant.now(), Instant.now().plusSeconds(300), Map.of("alg", "RS256"), claims);
        SecurityContextHolder.getContext().setAuthentication(new AdmissionJwtAuthentication(jwt, List.of(), admitted));
    }

    @Test
    void userId_returnsTheAdmittedId() {
        signedInAs(Optional.of(new AdmittedUser(7L, null)));
        assertThat(currentUser.userId()).isEqualTo(7L);
    }

    @Test
    void displayCurrencyPreference_presentAndAbsent() {
        signedInAs(Optional.of(new AdmittedUser(7L, "USD")));
        assertThat(currentUser.displayCurrencyPreference()).contains("USD");

        signedInAs(Optional.of(new AdmittedUser(7L, null)));
        assertThat(currentUser.displayCurrencyPreference()).isEmpty();
    }

    @Test
    void authenticatedButUnknownIdentity_isForbidden() {
        signedInAs(Optional.empty());
        assertThatThrownBy(currentUser::userId).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(currentUser::displayCurrencyPreference).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void nonEnrichedAuthentication_isForbidden() {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("someone", "secret", "ROLE_ADMIN"));
        assertThatThrownBy(currentUser::userId).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(currentUser::identity).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void userId_withNoAuthenticationAtAll_isAWiringBug() {
        // A scheduler or seeder reaching a user-scoped service: fail loudly, not as a 403.
        assertThatThrownBy(currentUser::userId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a request");
        assertThatThrownBy(currentUser::identity).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void identity_readsTheClaims_forAnUnadmittedCaller() {
        signedInAs(
                Optional.empty(),
                Map.of(
                        "iss",
                        "https://issuer.invalid/",
                        "sub",
                        "auth0|someone",
                        EMAIL_CLAIM,
                        "Someone@Example.com",
                        EMAIL_VERIFIED_CLAIM,
                        true));

        assertThat(currentUser.identity())
                .isEqualTo(
                        new ClaimedIdentity("https://issuer.invalid/", "auth0|someone", "Someone@Example.com", true));
    }

    @Test
    void identity_withoutEmailClaims_isUnverifiedAndEmailless() {
        signedInAs(Optional.empty());

        ClaimedIdentity identity = currentUser.identity();
        assertThat(identity.email()).isNull();
        assertThat(identity.emailVerified()).isFalse();
    }

    @Test
    void identity_withABlankSubject_isForbidden() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https://issuer.invalid/");
        claims.put("sub", " ");
        signedInAs(Optional.empty(), claims);

        assertThatThrownBy(currentUser::identity).isInstanceOf(ForbiddenException.class);
    }
}
