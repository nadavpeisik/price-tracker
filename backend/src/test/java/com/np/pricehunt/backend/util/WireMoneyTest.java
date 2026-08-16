package com.np.pricehunt.backend.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class WireMoneyTest {

    @Test
    void nullStaysNull() {
        // "No latest price" and "not convertible" are real states the UI renders as a placeholder.
        assertThat(WireMoney.decimalString(null)).isNull();
    }

    @Test
    void padsToScaleFour() {
        // The same amount reaches this method with different scales depending on whether it has
        // round-tripped numeric(19,4) yet; the wire spelling must not depend on that.
        assertThat(WireMoney.decimalString(new BigDecimal("100"))).isEqualTo("100.0000");
        assertThat(WireMoney.decimalString(new BigDecimal("19.99"))).isEqualTo("19.9900");
    }

    @Test
    void keepsAnAmountAlreadyAtScaleFour() {
        assertThat(WireMoney.decimalString(new BigDecimal("363.6364"))).isEqualTo("363.6364");
    }

    @Test
    void roundsHalfUpAtTheScaleBoundary() {
        // The tie is the whole reason this rounds HALF_UP rather than refusing over-scale input:
        // Postgres numeric rounds half away from zero, which for a positive price is the same thing.
        assertThat(WireMoney.decimalString(new BigDecimal("1234.56785"))).isEqualTo("1234.5679");
        assertThat(WireMoney.decimalString(new BigDecimal("1234.56784"))).isEqualTo("1234.5678");
    }

    @Test
    void neverEmitsScientificNotation() {
        assertThat(WireMoney.decimalString(new BigDecimal("1E+3"))).isEqualTo("1000.0000");
    }
}
