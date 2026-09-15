package com.np.pricehunt.backend.auth;

import com.np.pricehunt.backend.config.IdentityClaimProperties;
import com.np.pricehunt.backend.exception.ForbiddenException;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.stereotype.Component;

/**
 * The calling account, read off the enriched principal (issues #245, #248). Services ask for {@link
 * #userId()} and never see the JWT. The {@code app_user} row was looked up once, when the bearer token was
 * authenticated ({@link EnrichingJwtAuthenticationConverter}); this class runs no query of its own and
 * nothing is cached across requests, so there is no staleness window and no second lookup per consumer.
 *
 * <p>{@link #identity()} is the one accessor that works for a caller with no account: what the token
 * claims, read here because only this package may touch the security context (issue #249).
 */
@Component
@RequiredArgsConstructor
public class CurrentUser {

    private final IdentityClaimProperties claims;

    /**
     * @return the internal id of the admitted caller
     * @throws IllegalStateException when there is no authentication at all — a wiring bug, e.g. a
     *     user-scoped service reached from a scheduler rather than a request
     * @throws ForbiddenException when the caller carries no resolvable identity or no account row
     */
    public long userId() {
        return requireAdmittedUser().id();
    }

    /** The caller's stored display currency, or empty for "no preference". */
    public Optional<String> displayCurrencyPreference() {
        return Optional.ofNullable(requireAdmittedUser().displayCurrency());
    }

    /**
     * What the token says about the caller, admitted or not: the identity key and the provider's email
     * claim. Invitation redemption reads this; it is exactly the caller {@link #userId()} refuses.
     *
     * @throws IllegalStateException when there is no authentication at all
     * @throws ForbiddenException when the authentication is not a decoded bearer token or names no identity
     */
    public ClaimedIdentity identity() {
        Jwt token = requireAuthentication().getCredentials();
        String issuer = token.getClaimAsString(JwtClaimNames.ISS);
        String sub = token.getClaimAsString(JwtClaimNames.SUB);
        if (issuer == null || issuer.isBlank() || sub == null || sub.isBlank()) {
            throw notAdmitted();
        }
        return new ClaimedIdentity(
                issuer,
                sub,
                token.getClaimAsString(claims.email()),
                Boolean.TRUE.equals(token.getClaimAsBoolean(claims.emailVerified())));
    }

    private static AdmittedUser requireAdmittedUser() {
        return requireAuthentication().admittedUser().orElseThrow(CurrentUser::notAdmitted);
    }

    private static AdmissionJwtAuthentication requireAuthentication() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new IllegalStateException(
                    "No authentication in the security context: a user-scoped operation ran outside a request");
        }
        if (authentication instanceof AdmissionJwtAuthentication admissionAuthentication) {
            return admissionAuthentication;
        }
        throw notAdmitted();
    }

    private static ForbiddenException notAdmitted() {
        return new ForbiddenException("This identity has no account here");
    }
}
