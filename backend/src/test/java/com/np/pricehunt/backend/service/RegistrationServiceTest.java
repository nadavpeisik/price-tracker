package com.np.pricehunt.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.np.pricehunt.backend.auth.ClaimedIdentity;
import com.np.pricehunt.backend.config.RegistrationProperties;
import com.np.pricehunt.backend.domain.AppUser;
import com.np.pricehunt.backend.exception.ForbiddenException;
import com.np.pricehunt.backend.repository.AppUserRepository;
import com.np.pricehunt.backend.repository.InvitationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The one branch of {@link RegistrationService#provision} that the route test cannot reach: what a
 * caller sees when a concurrent request for the SAME identity provisioned the account while this one
 * waited on the invitation row lock. Reaching it needs the account to appear <em>between</em> two
 * lookups inside one call, which is a thread race in production and consecutive stubbing here.
 *
 * <p>Everything else about provisioning is proven end to end on real Postgres by
 * {@code invitation.InvitationRouteTest}; this class deliberately does not duplicate it.
 */
@ExtendWith(MockitoExtension.class)
class RegistrationServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-15T12:00:00Z");
    private static final ClaimedIdentity GUEST =
            new ClaimedIdentity("https://tenant.invalid/", "auth0|guest", "guest@example.com", true);

    @Mock
    private InvitationRepository invitations;

    @Mock
    private AppUserRepository appUsers;

    private RegistrationService registration;

    @BeforeEach
    void setUp() {
        registration = new RegistrationService(
                invitations, appUsers, new RegistrationProperties(true), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void whenAConcurrentRequestProvisionedTheSameIdentity_theLoserIsAlreadyAdmitted_notFalselyUninvited() {
        // The account does not exist when the call starts, and does by the time the lock is released:
        // the winner created it and consumed the invitation, so the open-invitation query finds nothing.
        when(appUsers.findByIssuerAndSub(GUEST.issuer(), GUEST.sub()))
                .thenReturn(
                        Optional.empty(), Optional.of(AppUser.builder().id(7L).build()));
        when(invitations.findByEmailAndRedeemedAtIsNullAndRevokedAtIsNull("guest@example.com"))
                .thenReturn(Optional.empty());

        assertThat(registration.provision(GUEST)).isEqualTo(AdmissionOutcome.ALREADY_ADMITTED);
        verify(appUsers, never()).saveAndFlush(any(AppUser.class));
    }

    @Test
    void aGenuinelyUninvitedIdentity_isStillRefused() {
        // The same branch, with the second look agreeing with the first: nobody provisioned anything.
        when(appUsers.findByIssuerAndSub(anyString(), anyString())).thenReturn(Optional.empty());
        when(invitations.findByEmailAndRedeemedAtIsNullAndRevokedAtIsNull("guest@example.com"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> registration.provision(GUEST))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("No valid invitation for this identity");
        verify(appUsers, never()).saveAndFlush(any(AppUser.class));
    }
}
