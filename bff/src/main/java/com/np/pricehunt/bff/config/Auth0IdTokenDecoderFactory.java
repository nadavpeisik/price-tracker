package com.np.pricehunt.bff.config;

import java.util.concurrent.ConcurrentHashMap;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestOperations;

/**
 * Decodes ID tokens with the same three pieces Spring's {@link OidcIdTokenDecoderFactory} uses
 * (RS256 against the registration's JWKS, the OIDC ID-token validators, the OIDC claim types), with
 * one difference: the JWKS fetch runs on a client with timeouts. The framework factory hard-codes
 * {@code NimbusJwtDecoder.withJwkSetUri(uri).jwsAlgorithm(RS256).build()} and offers no hook for the
 * HTTP client, so the only way to own the fetch's timeouts is to own the factory.
 *
 * <p>Built once per registration and cached, as the framework does: the JWKS cache lives inside the
 * decoder, so a factory that rebuilt it per login would refetch the key set on every login.
 */
public class Auth0IdTokenDecoderFactory implements JwtDecoderFactory<ClientRegistration> {

    private final RestOperations jwksClient;
    private final ConcurrentHashMap<String, JwtDecoder> decodersByRegistrationId = new ConcurrentHashMap<>();

    public Auth0IdTokenDecoderFactory(RestOperations jwksClient) {
        this.jwksClient = jwksClient;
    }

    @Override
    public JwtDecoder createDecoder(ClientRegistration registration) {
        return decodersByRegistrationId.computeIfAbsent(registration.getRegistrationId(), id -> build(registration));
    }

    private JwtDecoder build(ClientRegistration registration) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(
                        registration.getProviderDetails().getJwkSetUri())
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .restOperations(jwksClient)
                .build();
        // Timestamps + issuer + audience/azp and the rest of OIDC Core 3.1.3.7, exactly what
        // DefaultOidcIdTokenValidatorFactory returns.
        decoder.setJwtValidator(JwtValidators.createDefaultWithValidators(new OidcIdTokenValidator(registration)));
        decoder.setClaimSetConverter(OidcIdTokenDecoderFactory.createDefaultClaimTypeConverter());
        return decoder;
    }
}
