package com.np.pricehunt.backend.auth;

import java.util.function.Supplier;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Admission: a request is granted only when its identity has an {@code app_user} row (issue #245).
 * Applied to every {@code /api/**} route in the filter chain, so "an unknown identity is rejected" holds
 * structurally rather than depending on each service. Since #248 the row was already resolved when the
 * token was authenticated ({@link EnrichingJwtAuthenticationConverter}), so this is a presence check on
 * the principal: no repository, nothing that can throw, which matters because nothing translates an
 * exception raised at this point.
 *
 * <p>An anonymous caller gets 401 rather than 403 from a denial here: {@code ExceptionTranslationFilter}
 * routes anonymous denials to the entry point.
 */
@Component
public class AdmissionAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        return new AuthorizationDecision(
                authentication.get() instanceof AdmissionJwtAuthentication admissionAuthentication
                        && admissionAuthentication.admittedUser().isPresent());
    }
}
