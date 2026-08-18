package com.np.pricehunt.backend.dto;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import java.time.Instant;

/**
 * One shop's listing under a product as the dashboard's expanded panel shows it (issue #157): the
 * shop's own price and the same amount in the display currency, side by side.
 *
 * <p>The vocabulary is the dashboard row's ({@link DashboardProductResponse}) minus the {@code best}
 * prefix, because the panel is the row unfolded: {@code priceConverted} is what the user compares,
 * {@code priceOriginal} is what the shop actually lists. Money is a decimal string for the reasons
 * recorded on the row DTO (issue #175).
 *
 * <p><b>The wire order is the display order.</b> The backend sorts, because only it holds the exact
 * decimals and the FX-normalized amounts; the client renders the list as received and does no money
 * arithmetic. The ordering rule itself lives with the query that applies it,
 * {@code ProductQueryService#getListings}.
 *
 * <p><b>Nullability.</b> {@code priceOriginal}/{@code priceOriginalCurrency} are null when the
 * listing has no <em>current</em> observation, and {@code availability} is then {@code UNKNOWN}.
 * {@code priceConverted}/{@code priceConvertedCurrency} are additionally null when the amount cannot
 * be converted (no rate snapshot yet, unknown currency); the original survives so the panel can still
 * show it. {@code shopName} and {@code url} are nullable for hand-inserted legacy rows only.
 * {@code lastChecked} is the listing's own timestamp and stays populated even when the observation is
 * too old to count — that is what tells "never checked" from "gone cold".
 */
public record ProductListingResponse(
        Long trackedItemId,
        String shopName,
        String url,
        String priceOriginal,
        String priceOriginalCurrency,
        String priceConverted,
        String priceConvertedCurrency,
        boolean conversionStale,
        AvailabilityStatus availability,
        Instant lastChecked) {}
