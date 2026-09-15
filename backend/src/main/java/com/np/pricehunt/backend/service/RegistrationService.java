package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.auth.ClaimedIdentity;
import com.np.pricehunt.backend.config.RegistrationProperties;
import com.np.pricehunt.backend.domain.AppUser;
import com.np.pricehunt.backend.domain.Invitation;
import com.np.pricehunt.backend.dto.CreateInvitationRequest;
import com.np.pricehunt.backend.dto.InvitationResponse;
import com.np.pricehunt.backend.dto.InvitationStatus;
import com.np.pricehunt.backend.exception.ForbiddenException;
import com.np.pricehunt.backend.exception.NotFoundException;
import com.np.pricehunt.backend.exception.ValidationException;
import com.np.pricehunt.backend.repository.AppUserRepository;
import com.np.pricehunt.backend.repository.InvitationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * How an identity comes to hold an account (issue #249): the one writer of {@code app_user} rows, and
 * the invitations that gate them. Named for the whole of that rather than for the invitation table,
 * because provisioning is what it guarantees — with {@code invite-only} off it creates accounts that
 * redeem nothing. Knows no caller: the identity it admits and the inviter it records arrive as
 * arguments, so this class may hold repositories where {@link InvitationService} may not (ArchUnit,
 * {@code TenancyBoundaryTest}). Every write is one transaction, read-write throughout because
 * redemption takes a row lock and Postgres refuses {@code FOR UPDATE} in a read-only one.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegistrationService {

    /** How long an invitation stays redeemable. Nobody chooses another lifetime today. */
    static final Duration VALIDITY = Duration.ofDays(7);

    private static final int MAX_EMAIL_LENGTH = 255;

    private final InvitationRepository invitations;
    private final AppUserRepository appUsers;
    private final RegistrationProperties registrationProperties;
    private final Clock clock;

    /**
     * Gives the identity an account, redeeming its open invitation on the way. Idempotent: an identity
     * that already has one leaves without touching an invitation, whether it had one when the call
     * started or acquired one from a concurrent call while this one waited for the invitation lock.
     *
     * @throws ForbiddenException when the token's email is missing or unverified, or when registration
     *     is invite-only and no open, unexpired invitation names that email
     */
    @Transactional
    public AdmissionOutcome provision(ClaimedIdentity identity) {
        if (appUsers.findByIssuerAndSub(identity.issuer(), identity.sub()).isPresent()) {
            return AdmissionOutcome.ALREADY_ADMITTED;
        }
        if (identity.email() == null || identity.email().isBlank() || !identity.emailVerified()) {
            throw new ForbiddenException(
                    "Verify the email address with your sign-in provider, then sign out and back in");
        }
        String email = normalize(identity.email());
        Optional<Invitation> locked = invitations.findByEmailAndRedeemedAtIsNullAndRevokedAtIsNull(email);
        // The clock is read after the lock: a request that waited on the row must not redeem an
        // invitation that expired while it waited.
        Instant now = clock.instant();
        Optional<Invitation> invitation =
                locked.filter(open -> open.getExpiresAt().isAfter(now));
        if (invitation.isEmpty()) {
            // Look again before refusing. A concurrent request for this same identity — two tabs
            // restored together is enough — may have provisioned the account and consumed the
            // invitation while this one waited on the row lock, and the first lookup above is too old
            // to know. Without this, that caller is told it has no invitation at the moment it has an
            // account, on a screen that offers no way forward but a manual reload.
            if (appUsers.findByIssuerAndSub(identity.issuer(), identity.sub()).isPresent()) {
                return AdmissionOutcome.ALREADY_ADMITTED;
            }
            if (registrationProperties.inviteOnly()) {
                throw new ForbiddenException("No valid invitation for this identity");
            }
            // Open registration locks nothing, so this narrows the same race rather than closing it:
            // two requests that both reach the insert still collide on uq_app_user_identity, and the
            // loser is a 500. Closing that needs a new transaction around the duplicate insert, which
            // is not worth its weight while invite-only is the default.
        }
        AppUser user = appUsers.saveAndFlush(AppUser.builder()
                .issuer(identity.issuer())
                .sub(identity.sub())
                .email(email)
                .createdAt(now)
                .build());
        invitation.ifPresent(redeemed -> {
            redeemed.setRedeemedAt(now);
            redeemed.setRedeemedById(user.getId());
        });
        log.info("Provisioned app_user id={} (invitation redeemed: {})", user.getId(), invitation.isPresent());
        return AdmissionOutcome.PROVISIONED;
    }

    /**
     * Invites an email, superseding any open invitation for it: re-inviting is how an admin extends an
     * expired one. Two admins inviting the same email at once meet {@code uq_invitation_open_email},
     * which the advice reports as a 409.
     */
    @Transactional
    public InvitationResponse invite(CreateInvitationRequest request, long inviterId) {
        // A literal JSON `null` body reaches here; an empty body is already a 400 upstream.
        if (request == null || request.email() == null) {
            throw new ValidationException("email is required");
        }
        String email = normalize(request.email());
        if (email.isEmpty() || !email.contains("@") || email.length() > MAX_EMAIL_LENGTH) {
            throw new ValidationException("email must be an address of at most 255 characters");
        }
        Instant now = clock.instant();
        invitations.findByEmailAndRedeemedAtIsNullAndRevokedAtIsNull(email).ifPresent(open -> {
            open.setRevokedAt(now);
            // Hibernate flushes inserts before updates, so without this flush the replacement row would
            // meet the partial unique index while the superseded one is still open.
            invitations.saveAndFlush(open);
        });
        Invitation created = invitations.saveAndFlush(Invitation.builder()
                .email(email)
                .invitedById(inviterId)
                .createdAt(now)
                .expiresAt(now.plus(VALIDITY))
                .build());
        return toResponse(created, now);
    }

    /**
     * Revokes an open invitation. Idempotent: an already revoked or already redeemed one is already
     * unusable, so the answer is the same success.
     *
     * @throws NotFoundException when no invitation has that id
     */
    @Transactional
    public void revoke(long id) {
        if (!invitations.existsById(id)) {
            throw new NotFoundException("Invitation not found");
        }
        invitations.revokeOpen(id, clock.instant());
    }

    @Transactional(readOnly = true)
    public List<InvitationResponse> list() {
        Instant now = clock.instant();
        return invitations.findAllByOrderByCreatedAtDescIdDesc().stream()
                .map(invitation -> toResponse(invitation, now))
                .toList();
    }

    /** One spelling per address: what the CHECK constraint enforces for the hand-written bootstrap row. */
    static String normalize(String email) {
        return email.strip().toLowerCase(Locale.ROOT);
    }

    private static InvitationResponse toResponse(Invitation invitation, Instant now) {
        return new InvitationResponse(
                invitation.getId(),
                invitation.getEmail(),
                statusOf(invitation, now),
                invitation.getCreatedAt(),
                invitation.getExpiresAt(),
                invitation.getRedeemedAt(),
                invitation.getRevokedAt());
    }

    private static InvitationStatus statusOf(Invitation invitation, Instant now) {
        if (invitation.getRedeemedAt() != null) {
            return InvitationStatus.REDEEMED;
        }
        if (invitation.getRevokedAt() != null) {
            return InvitationStatus.REVOKED;
        }
        return invitation.getExpiresAt().isAfter(now) ? InvitationStatus.PENDING : InvitationStatus.EXPIRED;
    }
}
