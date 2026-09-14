package com.np.pricehunt.backend.auth;

/**
 * The request-relevant slice of an {@code app_user} row, copied out of the entity when the bearer token
 * is authenticated (issue #248). A record rather than the entity because OSIV is off: the row is loaded on
 * a connection that is returned to the pool before any service transaction starts, so nothing lazy may
 * survive past this copy.
 *
 * @param id the internal account id every tenancy query is scoped by
 * @param displayCurrency the stored ISO 4217 preference, or null for "no preference"
 */
public record AdmittedUser(long id, String displayCurrency) {}
