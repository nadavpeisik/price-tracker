package com.np.pricehunt.backend.auth;

import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.authorization.AuthorizationResult;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.stereotype.Component;

/**
 * Admission: a request is granted only when its identity has an {@code app_user} row (issue #245).
 * Applied to every {@code /api/**} route in the filter chain, so "an unknown identity is rejected" holds
 * structurally rather than depending on each service. Until #246 scopes the services, without this any
 * Auth0 signup holding a token for this audience could mutate the shared catalog.
 *
 * <p>An anonymous caller gets 401 rather than 403 from a denial here: {@code ExceptionTranslationFilter}
 * routes anonymous denials to the entry point. Must never throw, since nothing translates an exception
 * raised at this point.
 */
@Component
@RequiredArgsConstructor
public class AdmissionAuthorizationManager implements AuthorizationManager<RequestAuthorizationContext> {

    private final CurrentUser currentUser;

    @Override
    public AuthorizationResult authorize(
            Supplier<? extends Authentication> authentication, RequestAuthorizationContext context) {
        return new AuthorizationDecision(
                currentUser.resolveUserId(authentication.get()).isPresent());
    }
}
