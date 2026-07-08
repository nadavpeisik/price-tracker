package com.np.pricehunt.backend.validator;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.TimeoutException;

/**
 * Resolves a host to its IP address(es) for the SSRF pre-scrape check (#139). A seam so tests can
 * inject a hermetic stub instead of hitting real DNS, and so the timeout/bulkhead policy lives in one
 * place ({@link SystemHostResolver}).
 *
 * <p>The two checked exceptions map to distinct HTTP statuses in {@link UrlValidator}:
 *
 * <ul>
 *   <li>{@link UnknownHostException} &rarr; 400 (the host does not resolve — typo/NXDOMAIN/scan).
 *   <li>{@link TimeoutException} &rarr; 504 (resolution did not finish within the configured budget).
 * </ul>
 *
 * <p>Resolver-<em>unavailable</em> conditions (bounded-pool saturation, thread interruption) surface as
 * the unchecked {@link HostResolutionUnavailableException} &rarr; 503, keeping this interface at two
 * checked types.
 */
@FunctionalInterface
public interface HostResolver {
    InetAddress[] resolve(String host) throws UnknownHostException, TimeoutException;
}
