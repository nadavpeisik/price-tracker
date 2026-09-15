package com.np.pricehunt.backend.controller;

import com.np.pricehunt.backend.dto.MeResponse;
import com.np.pricehunt.backend.service.AdmissionOutcome;
import com.np.pricehunt.backend.service.InvitationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calling account (issues #248, #249). {@code GET} is its settings as the UI sees them, covered by the
 * {@code /api/**} rule (authenticated + admitted), so for a signed-in browser it is also the first proof
 * that the identity has an account here: its 403 is what makes the SPA try {@code POST}. {@code POST}
 * creates the account from the caller's invitation and is the one {@code /api} route that needs only
 * authentication — an identity with no account is exactly who calls it.
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final DisplayCurrencyResolver displayCurrencyResolver;
    private final InvitationService invitationService;

    @GetMapping
    public MeResponse me() {
        // The effective value, validated, rather than the raw preference: what the dashboard rows will
        // actually quote, so the header label and the numbers can never disagree.
        return new MeResponse(displayCurrencyResolver.resolve(null));
    }

    /** 201 when the account was just created, 204 when the caller already had one. */
    @PostMapping
    public ResponseEntity<Void> provision() {
        AdmissionOutcome outcome = invitationService.provision();
        return ResponseEntity.status(
                        outcome == AdmissionOutcome.PROVISIONED ? HttpStatus.CREATED : HttpStatus.NO_CONTENT)
                .build();
    }
}
