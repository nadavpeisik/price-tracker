package com.np.pricehunt.backend.auth;

import com.np.pricehunt.backend.domain.AppUser;
import com.np.pricehunt.backend.repository.AppUserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.convert.converter.Converter;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.transaction.TransactionException;

/**
 * Turns a decoded token into an {@link AdmissionJwtAuthentication}: Boot's converter for the authorities, one
 * {@code app_user} lookup for the account (issue #248). This is the one place per request that reads
 * {@code app_user} for identity; everything downstream reads the principal.
 *
 * <p>Wraps rather than replaces Boot's property-built {@link JwtAuthenticationConverter}, so the
 * {@code authorities-claim-name} / {@code authority-prefix} properties keep doing the role mapping and
 * there is no second copy to drift. The identity key is the token's raw {@code (iss, sub)} pair, read as
 * strings rather than through {@link Jwt#getIssuer()}, whose {@code java.net.URL} round trip need not
 * preserve the bytes the stored issuer must match. No just-in-time creation (#245: any Auth0 self-signup
 * would otherwise pass invite-only admission) and no cross-request cache.
 *
 * <p>A database failure here is rethrown as {@link AuthenticationServiceException}: the bearer filter's
 * failure handler routes that type to the advice, which answers 503, the same posture as an identity
 * provider outage.
 *
 * <p>Registered as a bean by {@code SecurityConfig} rather than component-scanned: a {@code @WebMvcTest}
 * slice includes every scanned {@code Converter}, and this one needs a repository the slice does not have.
 */
@Slf4j
@RequiredArgsConstructor
public class EnrichingJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtAuthenticationConverter authoritiesConverter;
    private final AppUserRepository appUserRepository;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        AbstractAuthenticationToken baseAuthentication = authoritiesConverter.convert(jwt);
        return new AdmissionJwtAuthentication(jwt, baseAuthentication.getAuthorities(), resolveAdmittedUser(jwt));
    }

    private Optional<AdmittedUser> resolveAdmittedUser(Jwt jwt) {
        String issuer = jwt.getClaimAsString(JwtClaimNames.ISS);
        String sub = jwt.getClaimAsString(JwtClaimNames.SUB);
        if (issuer == null || issuer.isBlank() || sub == null || sub.isBlank()) {
            return Optional.empty();
        }
        Optional<AdmittedUser> admitted;
        try {
            admitted = appUserRepository
                    .findByIssuerAndSub(issuer, sub)
                    .map(EnrichingJwtAuthenticationConverter::snapshot);
        } catch (DataAccessException | TransactionException e) {
            // Both types are the store being down: a query that fails is a DataAccessException, but a
            // connection the repository proxy cannot even acquire to open its read-only transaction is
            // a CannotCreateTransactionException, which is NOT a DataAccessException. The second is what
            // a stopped Postgres or an exhausted pool actually produces.
            throw new AuthenticationServiceException("Account store unavailable", e);
        }
        if (admitted.isEmpty()) {
            // Temporary bootstrap channel until #249: relinking V15's placeholder row needs this exact
            // pair, and no other integrated path exposes it. Raw claims are not free just because they
            // are not secret, since logs and the database have different readers and retention, so drop
            // them once invitation redemption provisions accounts.
            log.warn("No app_user for issuer={} sub={}", issuer, sub);
        }
        return admitted;
    }

    private static AdmittedUser snapshot(AppUser user) {
        return new AdmittedUser(user.getId(), user.getDisplayCurrency());
    }
}
