package com.np.pricehunt.bff.token;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;

/**
 * Hands the proxy an authorized client whose access token is good for the next call, refreshing it
 * first when needed. The seam the issue asked for: today's implementation coordinates concurrent
 * refreshes in-process ({@link SingleFlightRefreshCoordinator}); a multi-instance deployment replaces
 * it with a store-backed one without touching the proxy or the session SQL.
 *
 * <p>Empty means the session has no usable tokens (attribute absent or unreadable, or the row is gone)
 * and the caller ends the session. A refresh that fails terminally throws
 * {@code SessionRevokedException} after invalidating; a transient failure throws
 * {@code IdentityProviderUnavailableException} and leaves the session intact.
 */
public interface RefreshCoordinator {

    Optional<OAuth2AuthorizedClient> authorizedClient(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication);
}
