package com.np.pricehunt.bff.token;

import com.np.pricehunt.bff.config.Auth0ClientRegistrationConfig;
import com.np.pricehunt.bff.config.SessionPolicyProperties;
import com.np.pricehunt.bff.exception.IdentityProviderUnavailableException;
import com.np.pricehunt.bff.exception.SessionRevokedException;
import com.np.pricehunt.bff.session.SessionInvalidation;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.stereotype.Component;

/**
 * One refresh per session at a time, in process. Parallel dashboard calls on a session whose access
 * token just expired would otherwise each send the same refresh token to Auth0; with rotation and a
 * zero reuse interval the second one is a replay, Auth0 revokes the whole family, and the user is
 * logged out by their own dashboard. So the first request to notice the expiry refreshes on its own
 * thread; the others wait on its future and use its result.
 *
 * <p>The fast path holds no lock: a token outside the skew window is returned as loaded. Expiry is the
 * exact expression Spring's {@code RefreshTokenOAuth2AuthorizedClientProvider} uses, on the same clock
 * and the same skew, so the two cannot disagree at the boundary (a request that demands a refresh the
 * provider declines would read as a revocation, see below).
 *
 * <p>Nothing is held across the Auth0 call but an in-memory future, and the winner's save is durable
 * before the future completes ({@code flush-mode=immediate}). One BFF process is assumed; that is the
 * issue's own stance until a multi-instance store exists. A crash after Auth0 rotated but before the
 * save lands makes the next refresh fail {@code invalid_grant} and the user re-logs in: accepted, and
 * the reason the reuse interval stays zero rather than papering over it.
 */
@Component
public class SingleFlightRefreshCoordinator implements RefreshCoordinator {

    /** Refresh this long before the access token's {@code exp}; also the provider's clock skew. */
    public static final Duration REFRESH_SKEW = Duration.ofSeconds(60);

    private static final Logger log = LoggerFactory.getLogger(SingleFlightRefreshCoordinator.class);
    private static final Set<String> TERMINAL_ERROR_CODES =
            Set.of(OAuth2ErrorCodes.INVALID_GRANT, OAuth2ErrorCodes.INVALID_TOKEN);

    private final OAuth2AuthorizedClientRepository clients;
    private final OAuth2AuthorizedClientManager manager;
    private final Clock clock;
    private final Duration waitTimeout;
    private final ConcurrentHashMap<String, CompletableFuture<OAuth2AuthorizedClient>> inFlightRefreshesBySessionId =
            new ConcurrentHashMap<>();

    public SingleFlightRefreshCoordinator(
            OAuth2AuthorizedClientRepository clients,
            OAuth2AuthorizedClientManager manager,
            Clock clock,
            SessionPolicyProperties policy) {
        this.clients = clients;
        this.manager = manager;
        this.clock = clock;
        this.waitTimeout = policy.refreshWaitTimeout();
    }

    @Override
    public Optional<OAuth2AuthorizedClient> authorizedClient(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        OAuth2AuthorizedClient current =
                clients.loadAuthorizedClient(Auth0ClientRegistrationConfig.REGISTRATION_ID, authentication, request);
        if (current == null) {
            return Optional.empty();
        }
        if (!expiringWithinSkew(current)) {
            return Optional.of(current);
        }
        // Non-null: loadAuthorizedClient above returns a client only once it has found this session.
        String sessionId = request.getSession(false).getId();
        CompletableFuture<OAuth2AuthorizedClient> myRefresh = new CompletableFuture<>();
        CompletableFuture<OAuth2AuthorizedClient> refreshAlreadyRunning =
                inFlightRefreshesBySessionId.putIfAbsent(sessionId, myRefresh);
        if (refreshAlreadyRunning == null) {
            return Optional.of(refreshAsWinner(sessionId, myRefresh, request, response, authentication));
        }
        return Optional.of(awaitWinner(refreshAlreadyRunning, request));
    }

    private boolean expiringWithinSkew(OAuth2AuthorizedClient client) {
        return clock.instant().isAfter(client.getAccessToken().getExpiresAt().minus(REFRESH_SKEW));
    }

    private OAuth2AuthorizedClient refreshAsWinner(
            String sessionId,
            CompletableFuture<OAuth2AuthorizedClient> myRefresh,
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) {
        try {
            OAuth2AuthorizedClient refreshed = refresh(request, response, authentication);
            myRefresh.complete(refreshed);
            return refreshed;
        } catch (RuntimeException e) {
            // Any failure releases the waiters now, not at their timeout.
            myRefresh.completeExceptionally(e);
            if (e instanceof SessionRevokedException) {
                SessionInvalidation.invalidate(request);
            }
            throw e;
        } finally {
            // Conditional: never evict a later refresh cycle's future.
            inFlightRefreshesBySessionId.remove(sessionId, myRefresh);
        }
    }

    private OAuth2AuthorizedClient refresh(
            HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
        OAuth2AuthorizedClient refreshed;
        try {
            // The manager re-loads through the repository (committed state), so it skips the refresh
            // when another request already rotated; on success its save is immediate.
            refreshed = manager.authorize(
                    OAuth2AuthorizeRequest.withClientRegistrationId(Auth0ClientRegistrationConfig.REGISTRATION_ID)
                            .principal(authentication)
                            .attribute(HttpServletRequest.class.getName(), request)
                            .attribute(HttpServletResponse.class.getName(), response)
                            .build());
        } catch (OAuth2AuthorizationException e) {
            throw classify(e);
        }
        // The provider returns the same client (or null) when it cannot refresh: no refresh token
        // (an Auth0 application without "Allow Offline Access"), or a row deleted mid-flight. Forwarding
        // a token about to die on every call would be a slow 401 drizzle; one loud re-login instead.
        if (refreshed == null || refreshed.getRefreshToken() == null || expiringWithinSkew(refreshed)) {
            throw new SessionRevokedException("The session's tokens cannot be refreshed");
        }
        log.info("Refreshed access token for {}", authentication.getName());
        return refreshed;
    }

    private static RuntimeException classify(OAuth2AuthorizationException e) {
        String code = e.getError().getErrorCode();
        if (TERMINAL_ERROR_CODES.contains(code)) {
            return new SessionRevokedException("Auth0 rejected the refresh token (" + code + ")", e);
        }
        return new IdentityProviderUnavailableException("Token refresh failed (" + code + ")", e);
    }

    private OAuth2AuthorizedClient awaitWinner(
            CompletableFuture<OAuth2AuthorizedClient> winner, HttpServletRequest request) {
        try {
            return winner.get(waitTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            // This caller only: the in-flight refresh goes on and may still land for the next call.
            throw new IdentityProviderUnavailableException(
                    "Waited " + waitTimeout + " for an in-flight token refresh", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IdentityProviderUnavailableException("Interrupted while waiting for a token refresh", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof SessionRevokedException revoked) {
                // Idempotent delete; also what expires the cookie on this response.
                SessionInvalidation.invalidate(request);
                throw new SessionRevokedException(revoked.getMessage(), revoked);
            }
            if (cause instanceof IdentityProviderUnavailableException unavailable) {
                throw new IdentityProviderUnavailableException(unavailable.getMessage(), unavailable);
            }
            throw new IllegalStateException("Token refresh failed in another request", cause);
        }
    }
}
