package com.np.pricehunt.backend.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class RestClientFactoriesTest {

    @Test
    void timed_buildsFactory_forPositiveDurations() {
        assertThat(RestClientFactories.timed(Duration.ofSeconds(5), Duration.ofSeconds(10)))
                .isNotNull();
    }

    @Test
    void timed_rejectsNullConnect() {
        assertThatThrownBy(() -> RestClientFactories.timed(null, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect");
    }

    @Test
    void timed_rejectsZeroConnect() {
        assertThatThrownBy(() -> RestClientFactories.timed(Duration.ZERO, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect");
    }

    @Test
    void timed_rejectsNegativeConnect() {
        assertThatThrownBy(() -> RestClientFactories.timed(Duration.ofSeconds(-1), Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connect");
    }

    @Test
    void timed_rejectsNullRead() {
        assertThatThrownBy(() -> RestClientFactories.timed(Duration.ofSeconds(5), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read");
    }

    @Test
    void timed_rejectsZeroRead() {
        assertThatThrownBy(() -> RestClientFactories.timed(Duration.ofSeconds(5), Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read");
    }

    @Test
    void timed_rejectsNegativeRead() {
        assertThatThrownBy(() -> RestClientFactories.timed(Duration.ofSeconds(5), Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read");
    }
}
