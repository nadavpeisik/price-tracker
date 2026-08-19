package com.np.pricehunt.backend.service.fx;

/**
 * The application's supply of exchange rates.
 *
 * <p>What separates this from an {@link FxRateSource} is the promise, not the signature: a snapshot
 * returned here is dated, non-empty, and carries no rate at or below zero, so a caller can divide by it
 * without checking. How that is achieved — which sources are tried, in what order — is behind the
 * interface, and {@link FailoverRateProvider} is the only implementation.
 */
public interface FxRateProvider {

    /** The latest usable snapshot, or throws if no source could supply one. */
    RateSnapshot fetchLatest();
}
