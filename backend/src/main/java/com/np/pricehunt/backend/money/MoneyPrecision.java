package com.np.pricehunt.backend.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The one scale every layer measures money at, and the single place that decides it (issue #175).
 *
 * <p>Money reaches four places that each used to pick their own scale: the {@code numeric(19,4)}
 * column, the FX converter's output, the acceptance policy's delta band, and the wire formatter. When
 * those disagree, a value can pass a check at one scale and be silently rounded to something else at
 * the next — which is exactly how a price of {@code 0.00004} once satisfied "price &gt; 0" and then
 * landed in the database as {@code 0.0000}. This type is deliberately not a general "scales" holder:
 * FX rates (scale 8) and percentages (scale 2) are different quantities and must not move when this
 * one does.
 */
public final class MoneyPrecision {

    /** Matches {@code numeric(19,4)}; {@code @Column(scale = SCALE)} keeps the two provably equal. */
    public static final int SCALE = 4;

    /**
     * PostgreSQL {@code numeric} rounds half away from zero, which for the positive amounts this
     * system permits is exactly {@code HALF_UP} — so normalizing here yields the digits the column
     * would have produced anyway, rather than a second opinion about them.
     */
    public static final RoundingMode ROUNDING_MODE = RoundingMode.HALF_UP;

    private MoneyPrecision() {}

    /**
     * @param amount nullable — "no price" is a real state and stays null rather than becoming zero
     * @return the amount as the database would store it
     */
    public static BigDecimal normalize(BigDecimal amount) {
        return amount == null ? null : amount.setScale(SCALE, ROUNDING_MODE);
    }
}
