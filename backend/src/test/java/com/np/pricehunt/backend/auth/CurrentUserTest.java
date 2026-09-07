package com.np.pricehunt.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.domain.AppUser;
import com.np.pricehunt.backend.exception.ForbiddenException;
import com.np.pricehunt.backend.repository.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Token → account resolution (#245): the raw {@code (iss, sub)} pair is the key, an unknown pair is a
 * 403 kind, and reaching this with no request at all is a wiring bug, not a client error.
 */
@ExtendWith(MockitoExtension.class)
class CurrentUserTest {

    private static final String ISSUER = "https://dev-tenant.eu.auth0.com/";
    private static final String SUB = "google-oauth2|123456";

    @Mock
    private AppUserRepository appUsers;

    @InjectMocks
    private CurrentUser currentUser;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    private static JwtAuthenticationToken jwt(String issuer, String sub) {
        Jwt token = new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("iss", issuer, "sub", sub, "aud", List.of("pricehunt-api")));
        return new JwtAuthenticationToken(token);
    }

    @Test
    void resolvesTheRawIssuerAndSubject_toTheAccountId() {
        when(appUsers.findByIssuerAndSub(ISSUER, SUB))
                .thenReturn(Optional.of(AppUser.builder().id(42L).build()));

        assertThat(currentUser.resolveUserId(jwt(ISSUER, SUB))).contains(42L);
        // The trailing slash reached the repository untouched: the lookup is byte-for-byte.
        verify(appUsers).findByIssuerAndSub(ISSUER, SUB);
    }

    @Test
    void unknownIdentity_resolvesEmpty_andUserIdIsForbidden() {
        when(appUsers.findByIssuerAndSub(ISSUER, SUB)).thenReturn(Optional.empty());
        SecurityContextHolder.getContext().setAuthentication(jwt(ISSUER, SUB));

        assertThat(currentUser.resolveUserId(jwt(ISSUER, SUB))).isEmpty();
        assertThatThrownBy(currentUser::userId).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void nonJwtAuthentication_resolvesEmpty_withoutTouchingTheRepository() {
        Authentication basic = new TestingAuthenticationToken("someone", "secret", "ROLE_ADMIN");

        assertThat(currentUser.resolveUserId(basic)).isEmpty();
        verifyNoInteractions(appUsers);
    }

    @Test
    void blankSubject_resolvesEmpty_withoutTouchingTheRepository() {
        assertThat(currentUser.resolveUserId(jwt(ISSUER, " "))).isEmpty();
        verifyNoInteractions(appUsers);
    }

    @Test
    void userId_withNoAuthenticationAtAll_isAWiringBug() {
        // A scheduler or seeder reaching a user-scoped service: fail loudly, not as a 403.
        assertThatThrownBy(currentUser::userId)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a request");
        verifyNoInteractions(appUsers);
    }

    @Test
    void userId_returnsTheAdmittedId() {
        when(appUsers.findByIssuerAndSub(ISSUER, SUB))
                .thenReturn(Optional.of(AppUser.builder().id(7L).build()));
        SecurityContextHolder.getContext().setAuthentication(jwt(ISSUER, SUB));

        assertThat(currentUser.userId()).isEqualTo(7L);
    }
}
