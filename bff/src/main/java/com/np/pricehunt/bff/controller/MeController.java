package com.np.pricehunt.bff.controller;

import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The logged-in user's profile from the ID token: what the SPA shows in its header. No {@code sub},
 * no tokens, no admission state (the SPA learns that from the backend's 403). {@code roles} are
 * presentation-only and login-time: a refresh renews access tokens, not the {@code OidcUser}, so a
 * role change shows up on the next login, while the backend authorizes from the fresh access token
 * regardless. #248 should treat them as a hint, never a gate.
 */
@RestController
public class MeController {

    public static final String PATH = "/bff/me";

    /** Namespaced claim the Auth0 post-login Action sets on the ID token as well as the access token. */
    static final String ROLES_CLAIM = "https://pricehunt.app/roles";

    public record MeResponse(String name, String email, boolean emailVerified, String picture, List<String> roles) {}

    @GetMapping(PATH)
    public MeResponse me(@AuthenticationPrincipal OidcUser user) {
        List<String> roles = user.getClaimAsStringList(ROLES_CLAIM);
        return new MeResponse(
                user.getFullName(),
                user.getEmail(),
                Boolean.TRUE.equals(user.getEmailVerified()),
                user.getPicture(),
                roles == null ? List.of() : List.copyOf(roles));
    }
}
