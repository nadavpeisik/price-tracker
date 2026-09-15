package com.np.pricehunt.backend.controller;

import com.np.pricehunt.backend.dto.CreateInvitationRequest;
import com.np.pricehunt.backend.dto.InvitationResponse;
import com.np.pricehunt.backend.service.InvitationService;
import com.np.pricehunt.backend.service.RegistrationService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Invitation management (issue #249). Admin-only: the {@code /api/admin/**} rule in {@code SecurityConfig}
 * requires {@code ROLE_ADMIN} and admission. Inviting goes through the caller-resolving service because
 * it records the inviter; listing and revoking need no caller.
 */
@RestController
@RequestMapping("/api/admin/invitations")
@RequiredArgsConstructor
public class InvitationAdminController {

    private final InvitationService invitationService;
    private final RegistrationService registration;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public InvitationResponse invite(@RequestBody CreateInvitationRequest request) {
        return invitationService.invite(request);
    }

    @GetMapping
    public List<InvitationResponse> list() {
        return registration.list();
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable long id) {
        registration.revoke(id);
    }
}
