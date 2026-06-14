package com.np.pricehunt.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.net.http.HttpClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.JdkClientHttpRequestFactory;

class RestClientFactoriesTest {

    @Test
    void timed_buildsFactory_forPositiveDurations() {
        assertThat(RestClientFactories.timed(
                        Duration.ofSeconds(5), Duration.ofSeconds(10), HttpClient.Version.HTTP_1_1))
                .isNotNull();
    }

    @Test
    void timed_rejectsNullConnect() {
        assertThatThrownBy(() -> RestClientFactories.timed(null, Duration.ofSeconds(10), HttpClient.Version.HTTP_1_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect");
    }

    @Test
    void timed_rejectsZeroConnect() {
        assertThatThrownBy(() ->
                        RestClientFactories.timed(Duration.ZERO, Duration.ofSeconds(10), HttpClient.Version.HTTP_1_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect");
    }

    @Test
    void timed_rejectsNegativeConnect() {
        assertThatThrownBy(() -> RestClientFactories.timed(
                        Duration.ofSeconds(-1), Duration.ofSeconds(10), HttpClient.Version.HTTP_1_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect");
    }

    @Test
    void timed_rejectsNullRead() {
        assertThatThrownBy(() -> RestClientFactories.timed(Duration.ofSeconds(5), null, HttpClient.Version.HTTP_1_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read");
    }

    @Test
    void timed_rejectsZeroRead() {
        assertThatThrownBy(() ->
                        RestClientFactories.timed(Duration.ofSeconds(5), Duration.ZERO, HttpClient.Version.HTTP_1_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read");
    }

    @Test
    void timed_rejectsNegativeRead() {
        assertThatThrownBy(() -> RestClientFactories.timed(
                        Duration.ofSeconds(5), Duration.ofSeconds(-1), HttpClient.Version.HTTP_1_1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read");
    }

    @Test
    void timed_rejectsNullVersion() {
        assertThatThrownBy(() -> RestClientFactories.timed(Duration.ofSeconds(5), Duration.ofSeconds(10), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
    }

    // Regression guard for the h2c bug: the requested HTTP version must actually reach the underlying
    // HttpClient. JdkClientHttpRequestFactory exposes no getter, so we reflect its wrapped client off the
    // real timed(...) output (not a seam) — if Spring renames the field this fails loudly rather than silently.
    @Test
    void timed_appliesHttp1_1AndConnectTimeout() throws Exception {
        HttpClient client = extractHttpClient(
                RestClientFactories.timed(Duration.ofSeconds(3), Duration.ofSeconds(10), HttpClient.Version.HTTP_1_1));
        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_1_1);
        assertThat(client.connectTimeout()).hasValue(Duration.ofSeconds(3));
    }

    @Test
    void timed_appliesHttp2() throws Exception {
        HttpClient client = extractHttpClient(
                RestClientFactories.timed(Duration.ofSeconds(5), Duration.ofSeconds(10), HttpClient.Version.HTTP_2));
        assertThat(client.version()).isEqualTo(HttpClient.Version.HTTP_2);
    }

    private static HttpClient extractHttpClient(JdkClientHttpRequestFactory factory) throws Exception {
        Field field = JdkClientHttpRequestFactory.class.getDeclaredField("httpClient");
        field.setAccessible(true);
        return (HttpClient) field.get(factory);
    }
}
