package com.np.pricehunt.backend.auth;

import com.np.pricehunt.backend.domain.AppUser;
import com.np.pricehunt.backend.exception.ForbiddenException;
import com.np.pricehunt.backend.repository.AppUserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Resolves the calling identity to an {@code app_user} id (issue #245). Services ask for {@link
 * #userId()} and never see the JWT; the identity key is the token's raw {@code (iss, sub)} pair, looked
 * up on {@code uq_app_user_identity}.
 *
 * <p>No just-in-time creation: an unknown identity is rejected, because letting a login provision an
 * account would let any Auth0 self-signup past invite-only admission. Only #249's invitation redemption
 * may create or relink one. No cache either, since one indexed lookup per request does not justify a
 * staleness window. Claims are read as raw strings rather than through {@link Jwt#getIssuer()}, whose
 * {@code java.net.URL} round trip need not preserve the bytes the stored issuer must match.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CurrentUser {

    private final AppUserRepository appUserRepository;

    /**
     * @return the internal id of the admitted caller
     * @throws IllegalStateException when there is no authentication at all — a wiring bug, e.g. a
     *     user-scoped service reached from a scheduler rather than a request
     * @throws ForbiddenException when the caller carries no resolvable identity or no account row
     */
    public long userId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new IllegalStateException(
                    "No authentication in the security context: a user-scoped operation ran outside a request");
        }
        return resolveUserId(authentication)
                .orElseThrow(() -> new ForbiddenException("This identity has no account here"));
    }

    /**
     * The shared resolution: present iff {@code authentication} is a bearer JWT whose non-blank
     * {@code iss} and {@code sub} name an existing {@code app_user}. Never throws, because admission
     * calls it inside the filter chain, where an application exception would surface as a 500.
     */
    Optional<Long> resolveUserId(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwtAuthentication)) {
            return Optional.empty();
        }
        Jwt jwt = jwtAuthentication.getToken();
        String issuer = jwt.getClaimAsString(JwtClaimNames.ISS);
        String sub = jwt.getClaimAsString(JwtClaimNames.SUB);
        if (issuer == null || issuer.isBlank() || sub == null || sub.isBlank()) {
            return Optional.empty();
        }
        Optional<Long> id = appUserRepository.findByIssuerAndSub(issuer, sub).map(AppUser::getId);
        if (id.isEmpty()) {
            // The dev-bootstrap hook until #249: this pair is what the one-off UPDATE relinks the
            // placeholder row to. Neither claim is a secret.
            log.warn("No app_user for issuer={} sub={}", issuer, sub);
        }
        return id;
    }
}
