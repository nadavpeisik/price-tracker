package com.np.pricehunt.backend.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class MoneyPrecisionTest {

    @Test
    void nullStaysNull() {
        assertThat(MoneyPrecision.normalize(null)).isNull();
    }

    @Test
    void padsAndTrimsToTheColumnScale() {
        assertThat(MoneyPrecision.normalize(new BigDecimal("100"))).isEqualTo(new BigDecimal("100.0000"));
        assertThat(MoneyPrecision.normalize(new BigDecimal("19.99"))).isEqualTo(new BigDecimal("19.9900"));
    }

    @Test
    void roundsHalfUpAtTheScaleBoundary() {
        assertThat(MoneyPrecision.normalize(new BigDecimal("1234.56785"))).isEqualTo(new BigDecimal("1234.5679"));
        assertThat(MoneyPrecision.normalize(new BigDecimal("1234.56784"))).isEqualTo(new BigDecimal("1234.5678"));
    }

    @Test
    void collapsesSubScaleAmountsToZero() {
        // Not a curiosity — this is the value that used to pass "price > 0" and then land as 0.0000.
        // Normalizing first is what lets the validator see it for what the column will make of it.
        assertThat(MoneyPrecision.normalize(new BigDecimal("0.00004")).signum()).isZero();
    }
}
