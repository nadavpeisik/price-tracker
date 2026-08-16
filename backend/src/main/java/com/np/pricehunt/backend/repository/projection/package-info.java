/**
 * Query projections — the shapes repositories return when a full entity would be waste.
 *
 * <p>These are part of the repository layer, not the API layer, and live here to say so: nothing in
 * this package is serialized to a response body, and a type that gains a wire contract belongs in
 * {@code dto} instead.
 */
package com.np.pricehunt.backend.repository.projection;
