package com.np.pricehunt.backend.validator;

import com.np.pricehunt.backend.config.UrlValidationProperties;
import jakarta.annotation.PreDestroy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Production {@link HostResolver}: a bounded bulkhead around the blocking, non-interruptible native DNS
 * lookup ({@code InetAddress.getAllByName}).
 *
 * <p><b>Why a dedicated platform-thread pool, not a naked {@code getAllByName}:</b> the JDK lookup is a
 * blocking native {@code getaddrinfo} that ignores thread interruption. Called inline on the servlet
 * thread, a slow/hostile DNS server ties up request threads with no timeout. This wraps it in a fixed
 * pool + bounded queue and uses {@link java.util.concurrent.Future#get(long, TimeUnit)} to free the
 * <em>request</em> thread at the timeout. Virtual threads are deliberately NOT used: {@code getaddrinfo}
 * pins the carrier thread under Loom and would starve the common ForkJoinPool.
 *
 * <p><b>Honest semantics:</b> {@code Future.get(timeout)} frees the request thread (caller gets a 504 at
 * ~timeout); the pool thread stays occupied until the OS resolver returns — {@code cancel(true)} cannot
 * interrupt the native call. So the bulkhead's guarantee is <em>bounded in-flight work</em> (&le; pool +
 * queue) plus fail-fast, NOT thread reclamation. The effective production lever for stuck lookups is a
 * low OS resolver timeout (e.g. {@code resolv.conf options timeout:2 attempts:1}). When threads and queue
 * are full, {@link ThreadPoolExecutor.AbortPolicy} rejects &rarr; {@link HostResolutionUnavailableException}
 * (503).
 */
@Slf4j
@Component
public class SystemHostResolver implements HostResolver, AutoCloseable {

    /**
     * The one blocking, checkable lookup call, isolated behind a throwing functional interface so a test
     * can inject a latching/throwing stub. {@link java.util.function.Function} cannot hold
     * {@code InetAddress::getAllByName} because it declares checked {@link UnknownHostException}.
     */
    @FunctionalInterface
    interface DnsLookup {
        InetAddress[] lookup(String host) throws UnknownHostException;
    }

    private final DnsLookup lookup;
    private final long timeoutMs;
    private final ThreadPoolExecutor pool;

    @Autowired
    public SystemHostResolver(UrlValidationProperties properties) {
        this(properties, InetAddress::getAllByName);
    }

    // Package-private seam: tests inject a deterministic lookup + a tiny timeout / pool.
    SystemHostResolver(UrlValidationProperties properties, DnsLookup lookup) {
        this.lookup = lookup;
        this.timeoutMs = properties.dnsResolveTimeout().toMillis();
        int n = properties.dnsResolverPoolSize();
        this.pool = new ThreadPoolExecutor(
                n,
                n,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(properties.dnsResolverQueueCapacity()),
                daemonThreadFactory("ssrf-dns-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException, TimeoutException {
        // A Callable would let the lookup throw its checked UnknownHostException directly (no
        // CompletionException tunnelling); FutureTask is used so pool.remove(task) compiles on eviction.
        FutureTask<InetAddress[]> task = new FutureTask<>(() -> lookup.lookup(host));
        try {
            pool.execute(task); // AbortPolicy throws RejectedExecutionException when threads + queue are full
        } catch (RejectedExecutionException e) {
            throw new HostResolutionUnavailableException("DNS resolver saturated for host: " + host, e);
        }
        try {
            return task.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            cancelAndEvict(task);
            throw e;
        } catch (InterruptedException e) {
            cancelAndEvict(task);
            Thread.currentThread().interrupt(); // restore the flag; do not swallow
            throw new HostResolutionUnavailableException("Interrupted while resolving host: " + host, e);
        } catch (ExecutionException e) {
            cancelAndEvict(task); // no-op if already ran, but keeps the failure path uniform
            Throwable cause = e.getCause();
            if (cause instanceof UnknownHostException uhe) {
                throw uhe; // the expected "does not resolve" case → 400 upstream
            }
            // A lookup bug must NOT masquerade as a user 400 — rethrow unchecked / wrap a checked cause.
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new IllegalStateException("Unexpected DNS lookup failure for host: " + host, cause);
        }
    }

    // cancel(true) cannot stop the native getaddrinfo already running, but pool.remove frees a still-queued
    // task's ArrayBlockingQueue slot immediately (a cancelled-but-queued task otherwise occupies its slot
    // until a free worker dequeues it — under slow DNS no worker is free, so the queue would fill with dead
    // tasks and needlessly 503). If the task is already running, remove no-ops and the result is discarded.
    private void cancelAndEvict(FutureTask<InetAddress[]> task) {
        task.cancel(true);
        pool.remove(task);
    }

    // Visible for testing: current depth of the bounded queue, so a test can deterministically wait for
    // saturation (worker + queue full) instead of sleeping a guessed interval.
    int queuedTaskCount() {
        return pool.getQueue().size();
    }

    @PreDestroy
    @Override
    public void close() {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(2, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ThreadFactory daemonThreadFactory(String prefix) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread t = new Thread(runnable, prefix + counter.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
