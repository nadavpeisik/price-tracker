package com.np.pricehunt.backend.validator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.np.pricehunt.backend.config.UrlValidationProperties;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Import;

/**
 * Drives {@link SystemHostResolver} through the package-private {@code (props, DnsLookup)} seam so the
 * timeout / saturation / eviction paths are exercised deterministically with a latching stub — never
 * real DNS.
 */
class SystemHostResolverTest {

    @Test
    void resolvesIpLiteralOffline() throws Exception {
        try (SystemHostResolver resolver = new SystemHostResolver(props(2000, 4, 8))) {
            InetAddress[] addrs = resolver.resolve("127.0.0.1");
            assertThat(addrs).isNotEmpty();
            assertThat(addrs[0].getHostAddress()).isEqualTo("127.0.0.1");
        }
    }

    @Test
    void unknownHost_propagatesUnknownHostException() throws Exception {
        SystemHostResolver.DnsLookup throwing = host -> {
            throw new UnknownHostException(host);
        };
        try (SystemHostResolver resolver = new SystemHostResolver(props(2000, 2, 4), throwing)) {
            assertThatThrownBy(() -> resolver.resolve("nope.invalid")).isInstanceOf(UnknownHostException.class);
        }
    }

    @Test
    void slowLookup_timesOut() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        SystemHostResolver.DnsLookup slow = host -> {
            awaitUninterruptibly(release); // simulate a non-interruptible native lookup
            return loopback();
        };
        SystemHostResolver resolver = new SystemHostResolver(props(40, 2, 4), slow);
        try {
            assertThatThrownBy(() -> resolver.resolve("slow.example")).isInstanceOf(TimeoutException.class);
        } finally {
            release.countDown(); // free the stuck worker BEFORE close so shutdown does not wait the full grace
            resolver.close();
        }
    }

    @Test
    void saturation_throwsHostResolutionUnavailable() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SystemHostResolver.DnsLookup latched = host -> {
            workerStarted.countDown();
            awaitUninterruptibly(release);
            return loopback();
        };
        // pool=1, queue=1, generous timeout so the queued get does not time out (which would evict it).
        SystemHostResolver resolver = new SystemHostResolver(props(5000, 1, 1), latched);
        Thread worker = runResolve(resolver, "a"); // occupies the single worker
        Thread queued = null;
        try {
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();
            queued = runResolve(resolver, "b"); // fills the single queue slot
            awaitCondition(() -> resolver.queuedTaskCount() == 1);

            // threads (1) + queue (1) full → AbortPolicy → HostResolutionUnavailableException.
            assertThatThrownBy(() -> resolver.resolve("c")).isInstanceOf(HostResolutionUnavailableException.class);
        } finally {
            release.countDown();
            join(worker);
            join(queued);
            resolver.close();
        }
    }

    @Test
    void timedOutQueuedTask_isEvicted_freeingCapacity() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SystemHostResolver.DnsLookup latched = host -> {
            workerStarted.countDown();
            awaitUninterruptibly(release);
            return loopback();
        };
        // Short timeout: every get() times out; pool=1, queue=1.
        SystemHostResolver resolver = new SystemHostResolver(props(50, 1, 1), latched);
        Thread worker = runResolve(resolver, "a"); // occupies the worker; its foreground get times out but the
        // native task keeps running (interrupt swallowed), so the single worker stays busy.
        try {
            assertThat(workerStarted.await(5, TimeUnit.SECONDS)).isTrue();

            // A queued task that times out must be evicted from the queue (pool.remove), not left holding
            // the slot. Run it and wait for the thread to finish (eviction done).
            Thread queued = runResolve(resolver, "b");
            join(queued);

            // If eviction works, the slot is free again → this enqueues and TIMES OUT (not REJECTED).
            assertThatThrownBy(() -> resolver.resolve("c")).isInstanceOf(TimeoutException.class);
        } finally {
            release.countDown();
            join(worker);
            resolver.close();
        }
    }

    @Test
    void springWiresAutowiredConstructor() {
        new ApplicationContextRunner().withUserConfiguration(WiringConfig.class).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(SystemHostResolver.class);
            assertThat(ctx).hasSingleBean(UrlValidator.class);
        });
    }

    @EnableConfigurationProperties(UrlValidationProperties.class)
    @Import({SystemHostResolver.class, UrlValidator.class})
    static class WiringConfig {}

    // --- helpers ---

    private static UrlValidationProperties props(long timeoutMs, int pool, int queue) {
        return new UrlValidationProperties(true, List.of(), Duration.ofMillis(timeoutMs), pool, queue);
    }

    private static Thread runResolve(SystemHostResolver resolver, String host) {
        Thread t = new Thread(() -> {
            try {
                resolver.resolve(host);
            } catch (Exception ignored) {
                // expected: timeout / rejection — this thread only exists to occupy a slot.
            }
        });
        t.setDaemon(true);
        t.start();
        return t;
    }

    private static void awaitCondition(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5s");
            }
            Thread.sleep(2);
        }
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        try {
            while (System.nanoTime() < deadline) {
                try {
                    if (latch.await(10, TimeUnit.SECONDS)) {
                        return;
                    }
                } catch (InterruptedException e) {
                    interrupted = true; // swallow: models a native getaddrinfo that ignores interruption
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void join(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static InetAddress[] loopback() {
        try {
            return new InetAddress[] {InetAddress.getByName("127.0.0.1")};
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }
}
