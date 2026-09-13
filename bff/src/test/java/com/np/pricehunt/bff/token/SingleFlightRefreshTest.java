package com.np.pricehunt.bff.token;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.np.pricehunt.bff.controller.MeController;
import com.np.pricehunt.bff.session.SessionAttributes;
import com.np.pricehunt.bff.testsupport.BffIntegrationTest;
import com.np.pricehunt.bff.testsupport.FakeAuth0;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Concurrent calls on one session whose access token expired: exactly one refresh, every caller
 * carries the rotated token, and the failure modes split terminal (401 + session gone) from transient
 * (503 + session intact).
 */
class SingleFlightRefreshTest extends BffIntegrationTest {

    private static final int CALLERS = 8;

    @Test
    void concurrentCalls_refreshOnce_allCarryTheNewToken() throws Exception {
        Browser browser = login();
        String refreshBefore = AUTH0.currentRefreshToken();
        clock.advance(Duration.ofMinutes(10));

        List<MvcResult> results = fireConcurrently(browser, CALLERS);

        assertThat(results).allSatisfy(result -> assertThat(result.getResponse().getStatus())
                .isEqualTo(200));
        assertThat(AUTH0.refreshCalls()).hasSize(1);
        assertThat(AUTH0.refreshCalls().get(0).form()).containsEntry("refresh_token", refreshBefore);
        assertThat(BACKEND.received()).hasSize(CALLERS);
        assertThat(BACKEND.received()).allSatisfy(received -> assertThat(received.header("Authorization"))
                .isEqualTo("Bearer " + AUTH0.currentAccessToken()));
        String stored = new String(attributeBytes(SessionAttributes.TOKENS), StandardCharsets.ISO_8859_1);
        assertThat(stored).contains(AUTH0.currentRefreshToken()).doesNotContain(refreshBefore);

        // The next call, still within the new token's lifetime, refreshes nothing.
        browser.perform(get("/bff/api/x")).andExpect(status().isOk());
        assertThat(AUTH0.refreshCalls()).hasSize(1);
    }

    @Test
    void refreshRejected_everyCallerGets401_sessionGone_cookieExpired() throws Exception {
        Browser browser = login();
        AUTH0.revokeRefreshTokens();
        clock.advance(Duration.ofMinutes(10));

        List<MvcResult> results = fireConcurrently(browser, CALLERS);

        assertThat(results).allSatisfy(result -> {
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
            assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                    .anyMatch(header -> header.startsWith(SESSION_COOKIE + "=") && header.contains("Max-Age=0"));
        });
        assertThat(AUTH0.refreshCalls()).hasSize(1);
        assertThat(sessionRows()).isZero();
        assertThat(BACKEND.received()).isEmpty();
        browser.perform(get(MeController.PATH)).andExpect(status().isUnauthorized());
    }

    @Test
    void transientFailure_is503_sessionIntact_nextCallRefreshes() throws Exception {
        Browser browser = login();
        clock.advance(Duration.ofMinutes(10));

        AUTH0.setTokenEndpointMode(FakeAuth0.TokenEndpointMode.CONNECTION_DROPPED);
        browser.perform(get("/bff/api/x")).andExpect(status().isServiceUnavailable());
        AUTH0.setTokenEndpointMode(FakeAuth0.TokenEndpointMode.SERVER_ERROR);
        browser.perform(get("/bff/api/x")).andExpect(status().isServiceUnavailable());
        assertThat(sessionRows()).isEqualTo(1);
        assertThat(BACKEND.received()).isEmpty();

        AUTH0.setTokenEndpointMode(FakeAuth0.TokenEndpointMode.NORMAL);
        browser.perform(get("/bff/api/x")).andExpect(status().isOk());
        assertThat(BACKEND.last().header("Authorization")).isEqualTo("Bearer " + AUTH0.currentAccessToken());
        assertThat(sessionRows()).isEqualTo(1);
    }

    @Test
    void waiterTimeout_is503ForThatCallerOnly_refreshStillLands() throws Exception {
        Browser browser = login();
        clock.advance(Duration.ofMinutes(10));
        AUTH0.holdTokenEndpoint();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<MvcResult> winner =
                    executor.submit(() -> browser.perform(get("/bff/api/x")).andReturn());
            // Only once the winner is inside the (held) token endpoint is the second call a waiter.
            waitUntil(() -> AUTH0.tokenEndpointArrivals() == 2);
            Future<MvcResult> waiter =
                    executor.submit(() -> browser.perform(get("/bff/api/x")).andReturn());

            MvcResult waited = waiter.get(10, TimeUnit.SECONDS);
            assertThat(waited.getResponse().getStatus()).isEqualTo(503);

            AUTH0.releaseTokenEndpoint();
            MvcResult won = winner.get(10, TimeUnit.SECONDS);
            assertThat(won.getResponse().getStatus()).isEqualTo(200);
        } finally {
            AUTH0.releaseTokenEndpoint();
            executor.shutdownNow();
        }

        assertThat(sessionRows()).isEqualTo(1);
        browser.perform(get("/bff/api/x")).andExpect(status().isOk());
        assertThat(AUTH0.refreshCalls()).hasSize(1);
        assertThat(BACKEND.last().header("Authorization")).isEqualTo("Bearer " + AUTH0.currentAccessToken());
    }

    @Test
    void tokenStillOutsideSkew_isNotRefreshed_insideSkewIs() throws Exception {
        Browser browser = login();
        // 60 s skew: one second before the window is outside, one second into it is inside.
        clock.advance(FakeAuth0.INITIAL_ACCESS_TOKEN_LIFETIME
                .minus(SingleFlightRefreshCoordinator.REFRESH_SKEW)
                .minusSeconds(1));
        browser.perform(get("/bff/api/x")).andExpect(status().isOk());
        assertThat(AUTH0.refreshCalls()).isEmpty();
        clock.advance(Duration.ofSeconds(2));
        browser.perform(get("/bff/api/x")).andExpect(status().isOk());
        assertThat(AUTH0.refreshCalls()).hasSize(1);
    }

    private List<MvcResult> fireConcurrently(Browser browser, int callers) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<MvcResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(executor.submit(() -> {
                    go.await();
                    return browser.perform(get("/bff/api/x")).andReturn();
                }));
            }
            go.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("Condition not met in time");
            }
            Thread.sleep(20);
        }
    }
}
