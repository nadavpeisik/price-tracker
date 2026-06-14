package com.np.pricehunt.backend.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Builds {@link JdkClientHttpRequestFactory} instances with explicit timeouts, centralizing the
 * one subtle rule every HTTP client in this codebase needs: the connect timeout lives on the
 * underlying {@link HttpClient}, while the read timeout lives on the factory (the factory exposes
 * only {@code setReadTimeout}). Keeping it in one place stops that knowledge from drifting across
 * call sites.
 *
 * <p>Callers own the {@code clone()} of the shared {@code RestClient.Builder} bean before applying
 * the returned factory, so per-client timeouts don't leak onto every other consumer.
 *
 * <p>Each call allocates a fresh {@link HttpClient} (which owns connection-pool and selector threads),
 * so this is meant for one-time construction of singleton clients at bean-init — not per-request use.
 *
 * <p>The HTTP {@link HttpClient.Version} is a <em>required, per-caller</em> argument — never a silent
 * default. The JDK {@code HttpClient} defaults to HTTP/2; over cleartext {@code http://} that makes it
 * attempt an HTTP/2 upgrade (h2c) handshake, which HTTP/1.1-only servers (e.g. the uvicorn scraper)
 * reject with a 400. So a cleartext local service must be called with {@link HttpClient.Version#HTTP_1_1};
 * an HTTPS service can use {@link HttpClient.Version#HTTP_2} (negotiated via ALPN, with clean HTTP/1.1
 * fallback). Forcing each caller to state the version keeps that choice visible at the call site.
 */
public final class RestClientFactories {

    private RestClientFactories() {}

    public static JdkClientHttpRequestFactory timed(Duration connect, Duration read, HttpClient.Version version) {
        // Fail fast with a clear message: a misconfigured (null/zero/negative) timeout — or a missing
        // HTTP version — should surface here at bean creation, not as an opaque downstream error.
        requirePositive(connect, "connect");
        requirePositive(read, "read");
        if (version == null) {
            throw new IllegalArgumentException("version must not be null");
        }
        HttpClient httpClient =
                HttpClient.newBuilder().version(version).connectTimeout(connect).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(read);
        return factory;
    }

    private static void requirePositive(Duration timeout, String name) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException(name + " timeout must be a positive duration, but was: " + timeout);
        }
    }
}
