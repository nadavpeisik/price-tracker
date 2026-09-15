package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.auth.CurrentUser;
import com.np.pricehunt.backend.dto.CreateInvitationRequest;
import com.np.pricehunt.backend.dto.InvitationResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * The two registration operations that need the caller (issue #249): provisioning, which needs the
 * token's claimed identity, and inviting, which records who invited. Resolves the caller and delegates;
 * holds no repository, so it may hold {@link CurrentUser} (ArchUnit, {@code TenancyBoundaryTest}).
 * Listing and revoking invitations need no caller and go to {@link RegistrationService} directly.
 */
@Service
@RequiredArgsConstructor
public class InvitationService {

    private final CurrentUser currentUser;
    private final RegistrationService registration;

    public AdmissionOutcome provision() {
        return registration.provision(currentUser.identity());
    }

    public InvitationResponse invite(CreateInvitationRequest request) {
        return registration.invite(request, currentUser.userId());
    }
}
