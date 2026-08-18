/**
 * Query projections — the shapes repositories return when a full entity would be waste.
 *
 * <p>These are part of the repository layer, not the API layer, and live here to say so: nothing in
 * this package is serialized to a response body, and a type that gains a wire contract belongs in
 * {@code dto} instead.
 *
 * <p>Names carry a purpose-bearing stem plus one of three suffixes, because the package is invisible
 * at the call site — a service reads {@code List<DashboardListingRef>}, not where it came from:
 *
 * <ul>
 *   <li><b>{@code …Row}</b> — an interface bound to a native query's column aliases. Every alias must
 *       be quoted camelCase or Postgres folds it to lowercase and the getters silently return null.
 *   <li><b>{@code …View}</b> — a record built by a JPQL constructor expression, shaped for one
 *       caller. Breaks on constructor-signature drift, not on aliases.
 *   <li><b>{@code …Ref}</b> — an identity tuple: ids plus at most an identifying label, never a
 *       measure.
 * </ul>
 *
 * <p>The interface/native and record/JPQL pairing is this project's convention, not a Spring Data
 * rule — it supports either mapping for either query kind.
 */
package com.np.pricehunt.backend.repository.projection;
