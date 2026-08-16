package com.np.pricehunt.backend.util;

import com.np.pricehunt.backend.money.MoneyPrecision;
import java.math.BigDecimal;

/**
 * Formats a monetary amount for the JSON boundary, where money travels as a decimal string rather
 * than a number (issue #175).
 *
 * <p>JSON has no decimal type, so a {@code BigDecimal(19,4)} written as a JSON number is parsed by
 * {@code JSON.parse} into an IEEE-754 double. At this app's price ranges that round trip is lossless
 * in practice, which is exactly what makes it dangerous — nothing fails loudly, it surfaces later as
 * a rendered {@code 19.989999999999998}. Declaring response DTO fields {@code String} and formatting
 * them here makes the guarantee structural rather than dependent on serializer configuration, which
 * matters because Spring Boot 4 runs Jackson 3 for the web layer while Spring AI still binds Jackson
 * 2. Ratios such as {@code delta7d} are not amounts and stay JSON numbers.
 */
public final class WireMoney {

    private WireMoney() {}

    /**
     * @param amount nullable, because "no latest price" and "not convertible" are real states — they
     *     stay null rather than becoming a fabricated {@code "0"}
     * @return the amount at the shared money scale, never in scientific notation
     */
    public static String decimalString(BigDecimal amount) {
        // Normalized rather than formatted at whatever scale the source happens to carry: a record
        // that has not yet round-tripped Postgres holds what the extractor produced, while the same
        // row read back holds the column's scale — one price would otherwise have two spellings across
        // two endpoints. toPlainString then guarantees no scientific notation.
        BigDecimal normalized = MoneyPrecision.normalize(amount);
        return normalized == null ? null : normalized.toPlainString();
    }
}
