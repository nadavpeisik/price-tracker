package com.np.pricehunt.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;

/** The admission gate is exactly "the principal carries an account": nothing more, and it never throws. */
class AdmissionAuthorizationManagerTest {

    private final AdmissionAuthorizationManager admission = new AdmissionAuthorizationManager();
    private final RequestAuthorizationContext context = mock(RequestAuthorizationContext.class);

    private static AdmissionJwtAuthentication authenticationWith(Optional<AdmittedUser> admitted) {
        Jwt jwt = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("iss", "https://issuer.invalid/", "sub", "auth0|someone"));
        return new AdmissionJwtAuthentication(jwt, List.of(), admitted);
    }

    @Test
    void admittedIdentity_isGranted() {
        Authentication admitted = authenticationWith(Optional.of(new AdmittedUser(1L, null)));
        assertThat(admission.authorize(() -> admitted, context).isGranted()).isTrue();
    }

    @Test
    void authenticatedButUnknownIdentity_isDenied() {
        Authentication unknown = authenticationWith(Optional.empty());
        assertThat(admission.authorize(() -> unknown, context).isGranted()).isFalse();
    }

    @Test
    void anyOtherAuthenticationType_isDenied() {
        // Nothing but the enriched principal proves an account; a foreign token type cannot.
        Authentication basic = new TestingAuthenticationToken("someone", "secret", "ROLE_ADMIN");
        assertThat(admission.authorize(() -> basic, context).isGranted()).isFalse();
    }

    @Test
    void noAuthentication_isDenied_notAnException() {
        assertThat(admission.authorize(() -> null, context).isGranted()).isFalse();
    }
}
