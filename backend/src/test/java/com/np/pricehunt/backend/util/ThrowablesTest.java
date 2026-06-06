package com.np.pricehunt.backend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ThrowablesTest {

    @Test
    void summarize_nullThrowable_returnsNull() {
        assertThat(Throwables.summarize(null)).isNull();
    }

    @Test
    void summarize_throwableWithMessage_returnsClassNameAndMessage() {
        assertThat(Throwables.summarize(new RuntimeException("scraper down")))
                .isEqualTo("RuntimeException: scraper down");
    }

    @Test
    void summarize_throwableWithNullMessage_omitsTrailingColon() {
        assertThat(Throwables.summarize(new NullPointerException())).isEqualTo("NullPointerException");
    }

    @Test
    void summarize_throwableWithBlankMessage_omitsTrailingColon() {
        assertThat(Throwables.summarize(new IllegalStateException("   "))).isEqualTo("IllegalStateException");
    }
}
