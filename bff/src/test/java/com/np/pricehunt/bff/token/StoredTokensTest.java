package com.np.pricehunt.bff.token;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.bff.config.Auth0ClientRegistrationConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;

/** The record survives Java serialization intact and carries no client secret. */
class StoredTokensTest {

    private static final ClientRegistration REGISTRATION =
            Auth0ClientRegistrationConfig.registration("https://tenant.invalid/", "client-id", "the-client-secret");

    @Test
    void roundTrip_throughJavaSerialization() throws Exception {
        Instant issued = Instant.parse("2026-09-09T10:00:00Z");
        OAuth2AuthorizedClient client = new OAuth2AuthorizedClient(
                REGISTRATION,
                "auth0|u",
                new OAuth2AccessToken(
                        OAuth2AccessToken.TokenType.BEARER, "at", issued, issued.plusSeconds(300), Set.of("openid")),
                new OAuth2RefreshToken("rt", issued));

        byte[] bytes = serialize(StoredTokens.from(client));
        StoredTokens back = (StoredTokens) deserialize(bytes);
        OAuth2AuthorizedClient rebuilt = back.toAuthorizedClient(REGISTRATION);

        assertThat(rebuilt.getPrincipalName()).isEqualTo("auth0|u");
        assertThat(rebuilt.getAccessToken().getTokenValue()).isEqualTo("at");
        assertThat(rebuilt.getAccessToken().getExpiresAt()).isEqualTo(issued.plusSeconds(300));
        assertThat(rebuilt.getAccessToken().getScopes()).containsExactly("openid");
        assertThat(rebuilt.getRefreshToken().getTokenValue()).isEqualTo("rt");
        assertThat(rebuilt.getClientRegistration()).isSameAs(REGISTRATION);
        assertThat(new String(bytes, StandardCharsets.ISO_8859_1)).doesNotContain("the-client-secret");
        assertThat(back.toString()).doesNotContain("at").doesNotContain("rt").contains("auth0|u");
    }

    @Test
    void noRefreshToken_survives() throws Exception {
        StoredTokens tokens =
                new StoredTokens("p", "at", Instant.EPOCH, Instant.EPOCH.plusSeconds(1), null, null, null);
        StoredTokens back = (StoredTokens) deserialize(serialize(tokens));
        assertThat(back.scopes()).isEmpty();
        assertThat(back.toAuthorizedClient(REGISTRATION).getRefreshToken()).isNull();
    }

    private static byte[] serialize(Object value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ObjectOutputStream objects = new ObjectOutputStream(out)) {
            objects.writeObject(value);
        }
        return out.toByteArray();
    }

    private static Object deserialize(byte[] bytes) throws Exception {
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes))) {
            return in.readObject();
        }
    }
}
