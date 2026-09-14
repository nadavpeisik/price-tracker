package com.np.pricehunt.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.np.pricehunt.backend.exception.ForbiddenException;
import java.time.Instant;
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
 * wiring bug, not a client error. No repository is involved at any point.
 */
class CurrentUserTest {

    private final CurrentUser currentUser = new CurrentUser();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static void signedInAs(Optional<AdmittedUser> admitted) {
        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("iss", "https://issuer.invalid/", "sub", "auth0|someone"));
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
    }

    @Test
    void userId_withNoAuthenticationAtAll_isAWiringBug() {
        // A scheduler or seeder reaching a user-scoped service: fail loudly, not as a 403.
        assertThatThrownBy(currentUser::userId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a request");
    }
}
