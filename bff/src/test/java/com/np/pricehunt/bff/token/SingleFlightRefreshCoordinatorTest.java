package com.np.pricehunt.bff.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.np.pricehunt.bff.config.Auth0ClientRegistrationConfig;
import com.np.pricehunt.bff.config.SessionPolicyProperties;
import com.np.pricehunt.bff.exception.IdentityProviderUnavailableException;
import com.np.pricehunt.bff.exception.SessionRevokedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

/** The coordinator alone, with a scripted manager: winner/waiter split, classification, timeouts. */
class SingleFlightRefreshCoordinatorTest {

    private static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");
    private static final ClientRegistration REGISTRATION =
            Auth0ClientRegistrationConfig.registration("https://tenant.invalid/", "id", "secret");
    private static final Authentication USER = new TestingAuthenticationToken("auth0|u", null);

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AtomicReference<OAuth2AuthorizedClient> stored = new AtomicReference<>();
    private final AtomicInteger managerCalls = new AtomicInteger();
    private final CountDownLatch managerBlocked = new CountDownLatch(1);
    private final CountDownLatch releaseManager = new CountDownLatch(1);
    private volatile Supplier<OAuth2AuthorizedClient> managerOutcome = () -> fresh();

    private final OAuth2AuthorizedClientRepository repository = new OAuth2AuthorizedClientRepository() {
        @Override
        @SuppressWarnings("unchecked")
        public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
                String id, Authentication principal, HttpServletRequest request) {
            return (T) stored.get();
        }

        @Override
        public void saveAuthorizedClient(
                OAuth2AuthorizedClient client, Authentication p, HttpServletRequest rq, HttpServletResponse rs) {
            stored.set(client);
        }

        @Override
        public void removeAuthorizedClient(String id, Authentication p, HttpServletRequest rq, HttpServletResponse rs) {
            stored.set(null);
        }
    };

    private final OAuth2AuthorizedClientManager manager = request -> {
        managerCalls.incrementAndGet();
        managerBlocked.countDown();
        await(releaseManager);
        OAuth2AuthorizedClient outcome = managerOutcome.get();
        stored.set(outcome);
        return outcome;
    };

    private final SingleFlightRefreshCoordinator coordinator =
            new SingleFlightRefreshCoordinator(repository, manager, clock, policy(Duration.ofMillis(300)));

    @Test
    void freshToken_isReturnedWithoutTouchingTheManager() {
        stored.set(fresh());
        Optional<OAuth2AuthorizedClient> client =
                coordinator.authorizedClient(request(), new MockHttpServletResponse(), USER);
        assertThat(client).isPresent();
        assertThat(managerCalls).hasValue(0);
    }

    @Test
    void noStoredTokens_isEmpty() {
        assertThat(coordinator.authorizedClient(request(), new MockHttpServletResponse(), USER))
                .isEmpty();
    }

    @Test
    void skewBoundary_isTheProvidersOwn() {
        stored.set(expiringIn(SingleFlightRefreshCoordinator.REFRESH_SKEW.plusSeconds(1)));
        releaseManager.countDown();
        coordinator.authorizedClient(request(), new MockHttpServletResponse(), USER);
        assertThat(managerCalls).hasValue(0);

        stored.set(expiringIn(SingleFlightRefreshCoordinator.REFRESH_SKEW.minusSeconds(1)));
        coordinator.authorizedClient(request(), new MockHttpServletResponse(), USER);
        assertThat(managerCalls).hasValue(1);
    }

    @Test
    void concurrentCallers_oneRefresh_allGetTheResult() throws Exception {
        stored.set(expired());
        MockHttpSession session = new MockHttpSession();
        List<Future<Optional<OAuth2AuthorizedClient>>> futures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(6);
        try {
            for (int i = 0; i < 6; i++) {
                futures.add(executor.submit(
                        () -> coordinator.authorizedClient(request(session), new MockHttpServletResponse(), USER)));
            }
            assertThat(managerBlocked.await(5, TimeUnit.SECONDS)).isTrue();
            releaseManager.countDown();
            for (Future<Optional<OAuth2AuthorizedClient>> future : futures) {
                assertThat(future.get(5, TimeUnit.SECONDS)).hasValueSatisfying(client -> assertThat(
                                client.getAccessToken().getTokenValue())
                        .isEqualTo("fresh"));
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(managerCalls).hasValue(1);
    }

    @Test
    void waiterTimeout_is503_andDoesNotCancelTheRefresh() throws Exception {
        stored.set(expired());
        MockHttpSession session = new MockHttpSession();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Optional<OAuth2AuthorizedClient>> winner = executor.submit(
                    () -> coordinator.authorizedClient(request(session), new MockHttpServletResponse(), USER));
            assertThat(managerBlocked.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(
                            () -> coordinator.authorizedClient(request(session), new MockHttpServletResponse(), USER))
                    .isInstanceOf(IdentityProviderUnavailableException.class);

            releaseManager.countDown();
            assertThat(winner.get(5, TimeUnit.SECONDS)).isPresent();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidGrant_isRevocation_andInvalidatesTheSession() {
        stored.set(expired());
        managerOutcome = () -> {
            throw new ClientAuthorizationException(new OAuth2Error("invalid_grant"), "auth0");
        };
        releaseManager.countDown();
        MockHttpServletRequest request = request();
        assertThatThrownBy(() -> coordinator.authorizedClient(request, new MockHttpServletResponse(), USER))
                .isInstanceOf(SessionRevokedException.class);
        assertThat(request.getSession(false)).isNull();
    }

    @Test
    void transportFailure_isTransient_andKeepsTheSession() {
        stored.set(expired());
        managerOutcome = () -> {
            throw new ClientAuthorizationException(new OAuth2Error("invalid_token_response"), "auth0");
        };
        releaseManager.countDown();
        MockHttpServletRequest request = request();
        assertThatThrownBy(() -> coordinator.authorizedClient(request, new MockHttpServletResponse(), USER))
                .isInstanceOf(IdentityProviderUnavailableException.class);
        assertThat(request.getSession(false)).isNotNull();
    }

    @Test
    void refreshThatCannotHappen_isRevocation() {
        stored.set(expired());
        managerOutcome = () -> null;
        releaseManager.countDown();
        assertThatThrownBy(() -> coordinator.authorizedClient(request(), new MockHttpServletResponse(), USER))
                .isInstanceOf(SessionRevokedException.class);

        stored.set(expired());
        managerOutcome = () -> client("no-refresh", NOW.plusSeconds(600), null);
        assertThatThrownBy(() -> coordinator.authorizedClient(request(), new MockHttpServletResponse(), USER))
                .isInstanceOf(SessionRevokedException.class);
    }

    @Test
    void waitersSeeTheWinnersClassification() throws Exception {
        stored.set(expired());
        managerOutcome = () -> {
            throw new ClientAuthorizationException(new OAuth2Error("invalid_grant"), "auth0");
        };
        MockHttpSession session = new MockHttpSession();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> winner = executor.submit(
                    () -> coordinator.authorizedClient(request(session), new MockHttpServletResponse(), USER));
            assertThat(managerBlocked.await(5, TimeUnit.SECONDS)).isTrue();
            MockHttpServletRequest waiterRequest = request(session);
            AtomicReference<Throwable> waiterFailure = new AtomicReference<>();
            Thread waiter = new Thread(() -> {
                try {
                    coordinator.authorizedClient(waiterRequest, new MockHttpServletResponse(), USER);
                } catch (RuntimeException e) {
                    waiterFailure.set(e);
                }
            });
            waiter.start();
            // Parked in the timed get on the winner's future: that is what makes it a waiter.
            waitUntil(() -> waiter.getState() == Thread.State.TIMED_WAITING);
            releaseManager.countDown();
            assertThatThrownBy(() -> winner.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(SessionRevokedException.class);
            waiter.join(5_000);
            assertThat(waiterFailure.get()).isInstanceOf(SessionRevokedException.class);
            assertThat(waiterRequest.getSession(false))
                    .as("waiter invalidated its own session")
                    .isNull();
        } finally {
            executor.shutdownNow();
        }
        // One manager call: the waiter used the winner's outcome rather than refreshing itself.
        assertThat(managerCalls).hasValue(1);
    }

    // --- helpers ---

    private static SessionPolicyProperties policy(Duration waitTimeout) {
        return new SessionPolicyProperties(
                Duration.ofHours(24),
                Duration.ofHours(24),
                new SessionPolicyProperties.RememberMe(Duration.ofDays(90), Duration.ofDays(30)),
                waitTimeout);
    }

    private static MockHttpServletRequest request() {
        return request(new MockHttpSession());
    }

    private static MockHttpServletRequest request(MockHttpSession session) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setSession(session);
        return request;
    }

    private static OAuth2AuthorizedClient fresh() {
        return client("fresh", NOW.plusSeconds(600), "r");
    }

    private static OAuth2AuthorizedClient expired() {
        return client("stale", NOW.minusSeconds(1), "r");
    }

    private static OAuth2AuthorizedClient expiringIn(Duration in) {
        return client("edge", NOW.plus(in), "r");
    }

    private static OAuth2AuthorizedClient client(String access, Instant expiresAt, String refresh) {
        return new OAuth2AuthorizedClient(
                REGISTRATION,
                USER.getName(),
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER, access, expiresAt.minusSeconds(600), expiresAt, Set.of()),
                refresh == null ? null : new OAuth2RefreshToken(refresh, NOW));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met in time");
            }
            Thread.sleep(5);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new AssertionError("manager never released");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
