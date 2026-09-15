package com.np.pricehunt.backend.auth;

import java.util.Collection;
import java.util.Optional;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The request's {@code Authentication} once the bearer token has been decoded and its identity resolved
 * against {@code app_user} (issue #248): the enriched principal. Built once per request by {@link
 * EnrichingJwtAuthenticationConverter} and read by {@link AdmissionAuthorizationManager} and {@link
 * CurrentUser}, so the account row is looked up exactly once per request rather than by every consumer.
 *
 * <p>An empty principal is <em>authenticated but not admitted</em>: the token is valid and the identity
 * has no account here. That must stay an authenticated state, or admission would answer 401 (re-login)
 * for what is a 403 (not invited).
 */
public class AdmissionJwtAuthentication extends AbstractAuthenticationToken {

    private final transient Jwt token;
    private final transient Optional<AdmittedUser> admittedUser;

    public AdmissionJwtAuthentication(
            Jwt token, Collection<? extends GrantedAuthority> authorities, Optional<AdmittedUser> admittedUser) {
        super(authorities);
        this.token = token;
        this.admittedUser = admittedUser;
        setAuthenticated(true);
    }

    /** The decoded token: what {@link CurrentUser#identity()} reads for a caller with no account (#249). */
    @Override
    public Jwt getCredentials() {
        return token;
    }

    @Override
    public Optional<AdmittedUser> getPrincipal() {
        return admittedUser;
    }

    public Optional<AdmittedUser> admittedUser() {
        return admittedUser;
    }

    @Override
    public String getName() {
        return token.getSubject();
    }
}
