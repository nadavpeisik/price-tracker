package com.np.pricehunt.backend.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.domain.AppUser;
import com.np.pricehunt.backend.repository.AppUserRepository;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.transaction.CannotCreateTransactionException;

/**
 * Token → enriched principal (#248): one lookup keyed on the raw {@code (iss, sub)}, Boot's role mapping
 * carried through untouched, an unknown identity authenticated-but-empty, and a dead account store
 * surfacing as the 503 kind rather than a 500.
 */
@ExtendWith(MockitoExtension.class)
class EnrichingJwtAuthenticationConverterTest {

    private static final String ISSUER = "https://dev-tenant.eu.auth0.com/";
    private static final String SUB = "google-oauth2|123456";
    private static final String ROLES_CLAIM = "https://pricehunt.app/roles";

    @Mock
    private AppUserRepository appUsers;

    /** Configured the way Boot's properties do in production: namespaced roles claim, ROLE_ prefix. */
    private static JwtAuthenticationConverter bootsConverter() {
        var authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthoritiesClaimName(ROLES_CLAIM);
        authorities.setAuthorityPrefix("ROLE_");
        var converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }

    private EnrichingJwtAuthenticationConverter converter() {
        return new EnrichingJwtAuthenticationConverter(bootsConverter(), appUsers);
    }

    private static Jwt jwt(String issuer, String sub, List<String> roles) {
        return new Jwt(
                "token",
                Instant.now(),
                Instant.now().plusSeconds(300),
                Map.of("alg", "RS256"),
                Map.of("iss", issuer, "sub", sub, "aud", List.of("pricehunt-api"), ROLES_CLAIM, roles));
    }

    @Test
    void knownIdentity_isAdmitted_withIdAndPreference_andBootsAuthorities() {
        when(appUsers.findByIssuerAndSub(ISSUER, SUB))
                .thenReturn(Optional.of(
                        AppUser.builder().id(42L).displayCurrency("USD").build()));

        AbstractAuthenticationToken converted = converter().convert(jwt(ISSUER, SUB, List.of("ADMIN")));

        assertThat(converted).isInstanceOf(AdmissionJwtAuthentication.class);
        AdmissionJwtAuthentication authentication = (AdmissionJwtAuthentication) converted;
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.admittedUser()).contains(new AdmittedUser(42L, "USD"));
        // Boot's mapping survives the wrap, and so does Security 7's own FACTOR_BEARER marker.
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactlyInAnyOrder("ROLE_ADMIN", "FACTOR_BEARER");
        assertThat(authentication.getName()).isEqualTo(SUB);
        // The trailing slash reached the repository untouched: the lookup is byte-for-byte.
        verify(appUsers).findByIssuerAndSub(ISSUER, SUB);
    }

    @Test
    void unknownIdentity_isAuthenticatedButNotAdmitted() {
        when(appUsers.findByIssuerAndSub(ISSUER, SUB)).thenReturn(Optional.empty());

        AdmissionJwtAuthentication authentication =
                (AdmissionJwtAuthentication) converter().convert(jwt(ISSUER, SUB, List.of()));

        // Authenticated, so admission answers 403 rather than 401.
        assertThat(authentication.isAuthenticated()).isTrue();
        assertThat(authentication.admittedUser()).isEmpty();
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("FACTOR_BEARER");
    }

    @Test
    void blankSubject_isNotAdmitted_withoutTouchingTheRepository() {
        AdmissionJwtAuthentication authentication =
                (AdmissionJwtAuthentication) converter().convert(jwt(ISSUER, " ", List.of()));

        assertThat(authentication.admittedUser()).isEmpty();
        verifyNoInteractions(appUsers);
    }

    @Test
    void accountStoreFailure_isWrappedAsAuthenticationServiceException() {
        // Two shapes of "the database is down": a query that fails, and a connection the repository
        // proxy cannot acquire to open its transaction (the latter is what a stopped Postgres produces,
        // and it is not a DataAccessException).
        for (RuntimeException storeDown : List.of(
                new DataAccessResourceFailureException("connection refused"),
                new CannotCreateTransactionException("Could not open JPA EntityManager for transaction"))) {
            // doThrow: re-stubbing with when() would invoke the previous iteration's throwing stub.
            doThrow(storeDown).when(appUsers).findByIssuerAndSub(ISSUER, SUB);

            assertThatThrownBy(() -> converter().convert(jwt(ISSUER, SUB, List.of())))
                    .isInstanceOf(AuthenticationServiceException.class)
                    .hasMessage("Account store unavailable")
                    .hasCause(storeDown);
        }
    }
}
