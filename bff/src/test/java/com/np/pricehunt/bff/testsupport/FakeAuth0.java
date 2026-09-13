package com.np.pricehunt.bff.testsupport;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Plays the Auth0 tenant for the integration suites (issue #247), evolved from the backend's
 * {@code FakeIdentityProvider}: a loopback server with {@code /authorize} (auto-consents: 302 back with
 * a code; records the PKCE challenge, {@code audience} and {@code nonce}), {@code /oauth/token} (code
 * grant verifies the S256 verifier against the recorded challenge, refresh grant rotates and rejects a
 * reuse with {@code invalid_grant}; both check the client's Basic credentials), the JWKS, and
 * {@code /oidc/logout}. No discovery document: the BFF never asks for one.
 *
 * <p>Token lifetimes are well above the refresh skew; expiry in tests is driven by moving the injected
 * clock, never by short lifetimes or sleeps. The token endpoint can be switched to fail transiently
 * ({@link #setTokenEndpointMode}) or to block until released ({@link #holdTokenEndpoint}).
 */
public final class FakeAuth0 {

    public static final String CLIENT_ID = "test-client-id";
    public static final String CLIENT_SECRET = "test-client-secret";
    public static final String AUDIENCE = "pricehunt-api";
    public static final String ROLES_CLAIM = "https://pricehunt.app/roles";
    public static final String SUB = "auth0|test-user";
    public static final String SID = "session-abc";
    /**
     * Two lifetimes because Spring stamps a token's expiry from the wall clock while the suites expire
     * the login-time token by moving the application clock forward a few minutes: the token a refresh
     * mints must still be valid on that moved clock, so the refresh grant issues a much longer one.
     */
    public static final Duration INITIAL_ACCESS_TOKEN_LIFETIME = Duration.ofMinutes(5);

    public static final Duration REFRESHED_ACCESS_TOKEN_LIFETIME = Duration.ofHours(1);

    private static final String KEY_ID = "test-key";

    /** One recorded call to {@code /oauth/token}. */
    public record TokenCall(String grantType, Map<String, String> form) {}

    public enum TokenEndpointMode {
        NORMAL,
        /** Closes the connection without a response: a transport failure. */
        CONNECTION_DROPPED,
        /** Answers 500. */
        SERVER_ERROR
    }

    private final RSAKey key;
    private final HttpServer server;
    private final byte[] jwks;

    private final Map<String, Map<String, String>> authorizationParametersByCode = new HashMap<>();
    private final List<Map<String, String>> authorizeRequests = new CopyOnWriteArrayList<>();
    private final List<TokenCall> tokenCalls = new CopyOnWriteArrayList<>();
    private final List<Map<String, String>> logoutRequests = new CopyOnWriteArrayList<>();
    private final AtomicInteger tokenSerial = new AtomicInteger();
    private final AtomicInteger tokenEndpointArrivals = new AtomicInteger();
    private volatile String currentRefreshToken;
    private volatile TokenEndpointMode mode = TokenEndpointMode.NORMAL;
    private volatile CountDownLatch hold;
    private volatile Consumer<JWTClaimsSet.Builder> idTokenCustomizer = claims -> {};
    private volatile RSAKey signingKey;

    private FakeAuth0(RSAKey key, HttpServer server, byte[] jwks) {
        this.key = key;
        this.signingKey = key;
        this.server = server;
        this.jwks = jwks;
    }

    public static FakeAuth0 start() {
        try {
            RSAKey key = newKey();
            byte[] jwks = new JWKSet(key.toPublicJWK()).toString(true).getBytes(StandardCharsets.UTF_8);
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            FakeAuth0 fake = new FakeAuth0(key, server, jwks);
            server.createContext("/authorize", fake::authorize);
            server.createContext("/oauth/token", fake::token);
            server.createContext("/.well-known/jwks.json", fake::jwks);
            server.createContext("/oidc/logout", fake::logout);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the fake Auth0", e);
        }
    }

    private static RSAKey newKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(KEY_ID)
                    .build();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public void stop() {
        server.stop(0);
    }

    /** Trailing slash, like a real tenant issuer. */
    public String issuer() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    // --- test controls ---

    public void reset() {
        synchronized (authorizationParametersByCode) {
            authorizationParametersByCode.clear();
        }
        authorizeRequests.clear();
        tokenCalls.clear();
        tokenEndpointArrivals.set(0);
        logoutRequests.clear();
        currentRefreshToken = null;
        mode = TokenEndpointMode.NORMAL;
        hold = null;
        idTokenCustomizer = claims -> {};
        signingKey = key;
    }

    public List<Map<String, String>> authorizeRequests() {
        return authorizeRequests;
    }

    public List<TokenCall> tokenCalls() {
        return tokenCalls;
    }

    /** Counted at entry, before any hold: how many callers have reached the token endpoint. */
    public int tokenEndpointArrivals() {
        return tokenEndpointArrivals.get();
    }

    public List<TokenCall> refreshCalls() {
        return tokenCalls.stream()
                .filter(call -> "refresh_token".equals(call.grantType()))
                .toList();
    }

    public List<Map<String, String>> logoutRequests() {
        return logoutRequests;
    }

    public String currentRefreshToken() {
        return currentRefreshToken;
    }

    public String currentAccessToken() {
        return "access-" + tokenSerial.get();
    }

    /** Makes every stored refresh token stale: the next refresh grant is a reuse and fails invalid_grant. */
    public void revokeRefreshTokens() {
        currentRefreshToken = "revoked-" + tokenSerial.incrementAndGet();
    }

    public void setTokenEndpointMode(TokenEndpointMode mode) {
        this.mode = mode;
    }

    /** The next token calls block until {@link #releaseTokenEndpoint()}. */
    public CountDownLatch holdTokenEndpoint() {
        CountDownLatch latch = new CountDownLatch(1);
        this.hold = latch;
        return latch;
    }

    public void releaseTokenEndpoint() {
        CountDownLatch latch = this.hold;
        this.hold = null;
        if (latch != null) {
            latch.countDown();
        }
    }

    /** Overrides claims on the next ID tokens (wrong issuer, audience, expiry...). */
    public void customizeIdToken(Consumer<JWTClaimsSet.Builder> customizer) {
        this.idTokenCustomizer = customizer;
    }

    /** Signs the next ID tokens with a key the JWKS does not publish. */
    public void signWithUnpublishedKey() {
        this.signingKey = newKey();
    }

    // --- endpoints ---

    private void authorize(HttpExchange exchange) throws IOException {
        Map<String, String> params = query(exchange.getRequestURI());
        authorizeRequests.add(params);
        String code = "code-" + tokenSerial.incrementAndGet();
        synchronized (authorizationParametersByCode) {
            authorizationParametersByCode.put(code, params);
        }
        String location = params.get("redirect_uri") + "?code=" + code + "&state="
                + java.net.URLEncoder.encode(params.get("state"), StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void token(HttpExchange exchange) throws IOException {
        tokenEndpointArrivals.incrementAndGet();
        CountDownLatch latch = this.hold;
        if (latch != null) {
            await(latch);
        }
        TokenEndpointMode current = this.mode;
        if (current == TokenEndpointMode.CONNECTION_DROPPED) {
            exchange.close();
            return;
        }
        if (current == TokenEndpointMode.SERVER_ERROR) {
            respond(exchange, 500, "application/json", "{\"error\":\"server_error\"}");
            return;
        }
        if (!clientAuthenticated(exchange)) {
            respond(exchange, 401, "application/json", "{\"error\":\"invalid_client\"}");
            return;
        }
        Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        String grantType = form.get("grant_type");
        tokenCalls.add(new TokenCall(grantType, form));
        switch (grantType) {
            case "authorization_code" -> codeGrant(exchange, form);
            case "refresh_token" -> refreshGrant(exchange, form);
            default -> respond(exchange, 400, "application/json", "{\"error\":\"unsupported_grant_type\"}");
        }
    }

    private void codeGrant(HttpExchange exchange, Map<String, String> form) throws IOException {
        Map<String, String> authorize;
        synchronized (authorizationParametersByCode) {
            authorize = authorizationParametersByCode.remove(form.get("code"));
        }
        if (authorize == null) {
            respond(exchange, 400, "application/json", "{\"error\":\"invalid_grant\"}");
            return;
        }
        // PKCE proven end to end: the verifier must hash to the challenge the authorize request carried.
        if (!s256(form.get("code_verifier")).equals(authorize.get("code_challenge"))) {
            respond(exchange, 400, "application/json", "{\"error\":\"invalid_grant\",\"error_description\":\"PKCE\"}");
            return;
        }
        String refresh = "refresh-" + tokenSerial.incrementAndGet();
        currentRefreshToken = refresh;
        respond(exchange, 200, "application/json", tokenResponse(refresh, idToken(authorize.get("nonce"))));
    }

    private void refreshGrant(HttpExchange exchange, Map<String, String> form) throws IOException {
        String presented = form.get("refresh_token");
        if (presented == null || !presented.equals(currentRefreshToken)) {
            // Rotation with reuse detection: a stale token kills the family.
            currentRefreshToken = null;
            respond(exchange, 400, "application/json", "{\"error\":\"invalid_grant\",\"error_description\":\"reuse\"}");
            return;
        }
        String refresh = "refresh-" + tokenSerial.incrementAndGet();
        currentRefreshToken = refresh;
        respond(exchange, 200, "application/json", tokenResponse(refresh, null));
    }

    private String tokenResponse(String refreshToken, String idToken) {
        Duration lifetime = idToken == null ? REFRESHED_ACCESS_TOKEN_LIFETIME : INITIAL_ACCESS_TOKEN_LIFETIME;
        StringBuilder json = new StringBuilder("{\"access_token\":\"")
                .append(currentAccessToken())
                .append("\",\"token_type\":\"Bearer\",\"expires_in\":" + lifetime.toSeconds()
                        + ",\"scope\":\"openid profile email offline_access\"")
                .append(",\"refresh_token\":\"")
                .append(refreshToken)
                .append('"');
        if (idToken != null) {
            json.append(",\"id_token\":\"").append(idToken).append('"');
        }
        return json.append('}').toString();
    }

    private String idToken(String nonce) {
        Instant now = Instant.now();
        JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                .issuer(issuer())
                .subject(SUB)
                .audience(CLIENT_ID)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(Duration.ofMinutes(5))))
                .claim("nonce", nonce)
                .claim("sid", SID)
                .claim("name", "Test User")
                .claim("email", "test@example.com")
                .claim("email_verified", true)
                .claim("picture", "https://example.com/avatar.png")
                .claim(ROLES_CLAIM, List.of("ADMIN"));
        idTokenCustomizer.accept(claims);
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(), claims.build());
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException("Could not sign the test ID token", e);
        }
    }

    private void jwks(HttpExchange exchange) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, jwks.length);
        try (var body = exchange.getResponseBody()) {
            body.write(jwks);
        }
    }

    private void logout(HttpExchange exchange) throws IOException {
        Map<String, String> params = query(exchange.getRequestURI());
        logoutRequests.add(params);
        exchange.getResponseHeaders().add("Location", params.get("post_logout_redirect_uri"));
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    // --- helpers ---

    private static boolean clientAuthenticated(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        String expected = "Basic "
                + Base64.getEncoder()
                        .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        return expected.equals(header);
    }

    private static String s256(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> query(URI uri) {
        return form(uri.getRawQuery() == null ? "" : uri.getRawQuery());
    }

    private static Map<String, String> form(String encoded) {
        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            params.put(name, value);
        }
        return params;
    }

    private static void respond(HttpExchange exchange, int status, String contentType, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
