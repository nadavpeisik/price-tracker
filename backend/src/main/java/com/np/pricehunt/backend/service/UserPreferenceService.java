package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.auth.CurrentUser;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * What the calling account asked for, as opposed to what the request asked for (issue #248). Thin on
 * purpose: it is the service boundary a controller-package collaborator may depend on, where it may not
 * depend on {@link CurrentUser} directly (ArchUnit, {@code TenancyBoundaryTest}). It returns the raw
 * stored preference and folds in no default, so the one validation of a display currency stays in the
 * resolver, whichever source won.
 */
@Service
@RequiredArgsConstructor
public class UserPreferenceService {

    private final CurrentUser currentUser;

    /** The stored display-currency preference, or empty for "use the configured default". */
    public Optional<String> displayCurrencyPreference() {
        return currentUser.displayCurrencyPreference();
    }
}
