package com.np.pricehunt.backend.dto;

import java.util.Arrays;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * The dashboard's sort strategies (issue #146).
 *
 * <p>Named strategies, not column orderings: two of the three sort by values computed in Java — an
 * FX-converted price and a 7-day delta — that exist in no column, so a Spring Data {@code Sort} would
 * misrepresent them and invite someone to hand it to a repository.
 *
 * <p>The wire values are camelCase to match the frontend's {@code DashboardSort} union exactly. That
 * is also why {@link #fromParam} exists rather than plain {@code valueOf}: Spring's default enum
 * binding is {@code Enum.valueOf} — exact and case-sensitive — so {@code lowestCurrentPrice} would
 * never bind to a conventionally-named constant, and its 400 would not name the accepted values.
 */
public enum DashboardSortKey {
    NAME("name"),
    LOWEST_CURRENT_PRICE("lowestCurrentPrice"),
    BIGGEST_7D_DROP("biggest7dDrop");

    private final String param;

    DashboardSortKey(String param) {
        this.param = param;
    }

    public String param() {
        return param;
    }

    /** The default when a request omits {@code sort}: alphabetical, the one order with no prerequisites. */
    public static DashboardSortKey defaultKey() {
        return NAME;
    }

    /**
     * @param raw the {@code ?sort=} value; null or blank selects the default
     * @throws ResponseStatusException 400, listing every accepted value
     */
    public static DashboardSortKey fromParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return defaultKey();
        }
        String normalized = raw.trim();
        for (DashboardSortKey key : values()) {
            if (key.param.equalsIgnoreCase(normalized)) {
                return key;
            }
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_REQUEST, "Unknown sort '%s'; expected one of %s".formatted(raw, acceptedParams()));
    }

    private static String acceptedParams() {
        return Arrays.stream(values()).map(DashboardSortKey::param).collect(Collectors.joining(", "));
    }
}
