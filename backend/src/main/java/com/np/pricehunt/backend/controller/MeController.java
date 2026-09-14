package com.np.pricehunt.backend.controller;

import com.np.pricehunt.backend.dto.MeResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The calling account's settings as the UI sees them (issue #248). Covered by the {@code /api/**} rule
 * (authenticated + admitted), so for a signed-in browser this is also the first proof that the identity
 * has an account here: a 403 from this route is what the SPA renders as "not invited".
 */
@RestController
@RequestMapping("/api/me")
@RequiredArgsConstructor
public class MeController {

    private final DisplayCurrencyResolver displayCurrencyResolver;

    @GetMapping
    public MeResponse me() {
        // The effective value, validated, rather than the raw preference: what the dashboard rows will
        // actually quote, so the header label and the numbers can never disagree.
        return new MeResponse(displayCurrencyResolver.resolve(null));
    }
}
