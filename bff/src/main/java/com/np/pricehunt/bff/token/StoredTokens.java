package com.np.pricehunt.bff.token;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;
import java.util.Set;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

/**
 * What a session row actually stores for a logged-in user: the token values and their timestamps,
 * nothing else. Spring's {@link OAuth2AuthorizedClient} is not written as-is because it drags its
 * whole {@link ClientRegistration} into the serialized graph, client secret included, which would copy
 * the deployment's one confidential credential into every session row and every backup. Rebuilt into
 * an {@code OAuth2AuthorizedClient} on load with the live registration. As a side effect the session
 * bytes depend on this record's layout only, not on Spring Security's.
 *
 * <p>{@code principalName} lets the store reject a row whose tokens belong to a different identity than
 * the one authenticated in it.
 */
public record StoredTokens(
        String principalName,
        String accessToken,
        Instant accessIssuedAt,
        Instant accessExpiresAt,
        Set<String> scopes,
        String refreshToken,
        Instant refreshIssuedAt)
        implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public StoredTokens {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }

    /** Never the token values: a record's default {@code toString} would put them in any log line. */
    @Override
    public String toString() {
        return "StoredTokens[principalName=" + principalName + ", accessExpiresAt=" + accessExpiresAt
                + ", refreshToken=" + (refreshToken == null ? "absent" : "present") + "]";
    }

    public static StoredTokens from(OAuth2AuthorizedClient client) {
        OAuth2AccessToken access = client.getAccessToken();
        OAuth2RefreshToken refresh = client.getRefreshToken();
        return new StoredTokens(
                client.getPrincipalName(),
                access.getTokenValue(),
                access.getIssuedAt(),
                access.getExpiresAt(),
                access.getScopes(),
                refresh == null ? null : refresh.getTokenValue(),
                refresh == null ? null : refresh.getIssuedAt());
    }

    public OAuth2AuthorizedClient toAuthorizedClient(ClientRegistration registration) {
        OAuth2AccessToken access = new OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER, accessToken, accessIssuedAt, accessExpiresAt, scopes);
        OAuth2RefreshToken refresh =
                refreshToken == null ? null : new OAuth2RefreshToken(refreshToken, refreshIssuedAt);
        return new OAuth2AuthorizedClient(registration, principalName, access, refresh);
    }
}
