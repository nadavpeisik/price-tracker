package com.np.pricehunt.backend.dto;

import java.time.Instant;

/**
 * One day of the FX-normalized product series, stamped at that UTC day's midnight.
 *
 * <p>{@code price} is a decimal string — see {@link DashboardProductResponse} for why money never
 * crosses this boundary as a JSON number.
 */
public record TrendPointResponse(Instant t, String price, BestOfferResponse bestOffer) {}
