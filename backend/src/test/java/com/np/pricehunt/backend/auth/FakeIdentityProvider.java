package com.np.pricehunt.backend.auth;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Plays Auth0 for the security tests (issue #245): a throwaway RSA key pair, a loopback HTTP server
 * publishing its public half as a JWK Set, and signed tokens with the claims the real tenant would
 * mint. Pointing {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} at {@link #jwkSetUri()}
 * exercises Boot's production decoder mode — issuer, audience and timestamp validators included —
 * with no network and no secret, which is the "offline tests" constraint the issue sets.
 *
 * <p>One instance per owning test class: {@link #start()} in a static initializer, {@link #stop()} in
 * {@code @AfterAll}. Virtual threads, so the deliberately slow {@code /jwks/slow} path cannot block the
 * healthy one.
 */
public final class FakeIdentityProvider {

    /** Matches the shadow {@code issuer-uri} in {@code application-test.properties}. */
    public static final String ISSUER = "https://test-issuer.invalid/";

    static final String AUDIENCE = "pricehunt-api";
    static final String ROLES_CLAIM = "https://pricehunt.app/roles";
    public static final String DEFAULT_SUB = "auth0|test-user";
    static final String KEY_ID = "test-key";

    private final RSAKey key;
    private final HttpServer server;

    private FakeIdentityProvider(RSAKey key, HttpServer server) {
        this.key = key;
        this.server = server;
    }

    public static FakeIdentityProvider start() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            RSAKey key = new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(KEY_ID)
                    .build();
            byte[] jwks = new JWKSet(key.toPublicJWK()).toString(true).getBytes(StandardCharsets.UTF_8);

            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/jwks", exchange -> respond(exchange.getResponseBody(), exchange, jwks));
            server.createContext("/jwks/slow", exchange -> {
                sleepQuietly(5_000);
                respond(exchange.getResponseBody(), exchange, jwks);
            });
            server.start();
            return new FakeIdentityProvider(key, server);
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not start the fake identity provider", e);
        }
    }

    private static void respond(OutputStream body, com.sun.net.httpserver.HttpExchange exchange, byte[] jwks)
            throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, jwks.length);
        try (body) {
            body.write(jwks);
        }
    }

    // Sonar S2925 flags Thread.sleep in tests as a flakiness smell. Here the delay is the subject
    // under test: JwksReadTimeoutTest asserts the configured read timeout fires before this returns,
    // so an unresponsive endpoint is exactly what has to be simulated.
    @SuppressWarnings("java:S2925")
    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public void stop() {
        server.stop(0);
    }

    public String jwkSetUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }

    String slowJwkSetUri() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks/slow";
    }

    /** A token with the tenant's normal claims; {@code customize} overrides any of them. */
    String token(Consumer<JWTClaimsSet.Builder> customize) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(ISSUER)
                .subject(DEFAULT_SUB)
                .audience(AUDIENCE)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)));
        customize.accept(claims);
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims.build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the test token", e);
        }
    }

    String userToken() {
        return token(claims -> {});
    }

    public String userToken(String sub) {
        return token(claims -> claims.subject(sub));
    }

    String adminToken() {
        return token(claims -> claims.claim(ROLES_CLAIM, List.of("ADMIN")));
    }

    public String adminToken(String sub) {
        return token(claims -> claims.subject(sub).claim(ROLES_CLAIM, List.of("ADMIN")));
    }

    String expiredToken() {
        return token(claims -> claims.expirationTime(Date.from(Instant.now().minusSeconds(60))));
    }

    String wrongAudienceToken() {
        return token(claims -> claims.audience("some-other-api"));
    }

    String wrongIssuerToken() {
        return token(claims -> claims.issuer("https://another-tenant.invalid/"));
    }

    static String malformedToken() {
        return "not.a.jwt";
    }
}
