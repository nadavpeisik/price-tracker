package com.np.pricehunt.backend.auth;

import com.np.pricehunt.backend.exception.ForbiddenException;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * The calling account, read off the enriched principal (issues #245, #248). Services ask for {@link
 * #userId()} and never see the JWT. The {@code app_user} row was looked up once, when the bearer token was
 * authenticated ({@link EnrichingJwtAuthenticationConverter}); this class runs no query of its own and
 * nothing is cached across requests, so there is no staleness window and no second lookup per consumer.
 */
@Component
public class CurrentUser {

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

    private static AdmittedUser requireAdmittedUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new IllegalStateException(
                    "No authentication in the security context: a user-scoped operation ran outside a request");
        }
        if (authentication instanceof AdmissionJwtAuthentication admissionAuthentication) {
            return admissionAuthentication.admittedUser().orElseThrow(CurrentUser::notAdmitted);
        }
        throw notAdmitted();
    }

    private static ForbiddenException notAdmitted() {
        return new ForbiddenException("This identity has no account here");
    }
}
