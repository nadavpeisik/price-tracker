package com.np.pricehunt.backend.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One row of the tracked-items dashboard (issue #146).
 *
 * <p><b>Money is a decimal string, and the field types say so.</b> {@code BigDecimal(19,4)} does not
 * survive a round trip through a JSON number — a client parsing 1234.5600 into an IEEE double and
 * formatting it back can shift the last cent. Declaring these fields {@code String} and formatting via
 * {@code toPlainString()} makes that guarantee structural rather than dependent on serializer
 * configuration, which matters here because Spring Boot 4 runs Jackson 3 for the web layer while
 * Spring AI still binds Jackson 2. {@code delta7d} stays a number: it is a ratio, not an amount.
 *
 * <p><b>{@code listings} is absent, not empty.</b> Per-shop rows load on expand (#157), and an empty
 * array would be indistinguishable from "this product has no shops". The field simply does not exist
 * on this DTO, so the type system enforces the lazy fetch rather than a convention.
 *
 * <p>{@code imageUrl} and {@code category} are always null today — the columns do not exist yet
 * (#95). They are present so the frontend contract, which already renders a gradient fallback for a
 * null image, does not change shape when they land.
 *
 * @param bestPriceConverted the cheapest eligible offer in the requested display currency
 * @param bestPriceOriginal the same offer as the shop lists it, before conversion
 * @param conversionStale the FX rate used was over a week old — badge the converted price
 * @param delta7d percent change over seven days; null renders as "New", and is never a substitute
 *     for 0 (a genuine 0 means the price held steady)
 * @param sparkline FX-normalized daily series, oldest first
 */
public record DashboardProductResponse(
        Long id,
        String name,
        String imageUrl,
        String category,
        String bestPriceConverted,
        String bestPriceConvertedCurrency,
        String bestPriceOriginal,
        String bestPriceOriginalCurrency,
        String bestPriceShop,
        boolean conversionStale,
        LocalDate conversionAsOf,
        boolean mixedCurrencies,
        DashboardAvailabilityResponse availability,
        BigDecimal delta7d,
        List<DashboardPricePointResponse> sparkline) {}
