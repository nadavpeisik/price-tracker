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
 */
public final class RestClientFactories {

    private RestClientFactories() {}

    public static JdkClientHttpRequestFactory timed(Duration connect, Duration read) {
        // Fail fast with a clear message: a misconfigured (null/zero/negative) timeout property
        // should surface here at bean creation, not as an opaque downstream HttpClient error.
        requirePositive(connect, "connect");
        requirePositive(read, "read");
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(connect).build();
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
