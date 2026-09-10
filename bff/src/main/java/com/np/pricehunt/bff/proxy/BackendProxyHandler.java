package com.np.pricehunt.bff.proxy;

import com.np.pricehunt.bff.config.BackendProxyProperties;
import com.np.pricehunt.bff.config.CorrelationIdFilter;
import com.np.pricehunt.bff.config.RestClientFactories;
import com.np.pricehunt.bff.exception.BackendRejectedGatewayTokenException;
import com.np.pricehunt.bff.exception.BackendUnavailableException;
import com.np.pricehunt.bff.exception.InvalidProxyTargetException;
import com.np.pricehunt.bff.exception.SessionRevokedException;
import com.np.pricehunt.bff.session.SessionInvalidation;
import com.np.pricehunt.bff.token.RefreshCoordinator;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Forwards {@code /bff/api/**} to the backend with the session's bearer token: the minimum a proxy
 * needs, deliberately (#247 subtraction round). Buffered both ways, since request bodies are a few
 * hundred bytes of JSON and responses are dashboard pages of a few KB; nothing streams and no body is
 * handled off the request thread. Path and query are forwarded byte-for-byte as the browser sent
 * them, so repeated and percent-encoded parameters reach the backend unchanged.
 *
 * <p>Status codes pass through (403/404/409 are the SPA's to interpret) with one exception: a backend
 * 401 becomes a 502, because the token was just minted or refreshed by this process and a rejection
 * is a configuration fault, never "log in again". Any transport failure is one 502.
 */
@RestController
public class BackendProxyHandler {

    static final String PREFIX = "/bff";

    private static final Logger log = LoggerFactory.getLogger(BackendProxyHandler.class);

    private final RefreshCoordinator coordinator;
    private final RestClient backend;
    private final String baseUrl;

    public BackendProxyHandler(RefreshCoordinator coordinator, BackendProxyProperties properties) {
        this.coordinator = coordinator;
        this.baseUrl = properties.baseUrl();
        // Cleartext to the backend: HTTP/1.1, no h2c upgrade attempt.
        this.backend = RestClient.builder()
                .requestFactory(RestClientFactories.timed(
                        properties.connectTimeout(), properties.readTimeout(), HttpClient.Version.HTTP_1_1))
                .build();
    }

    @RequestMapping(
            path = PREFIX + "/api/**",
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PATCH, RequestMethod.DELETE})
    public void proxy(HttpServletRequest request, HttpServletResponse response, Authentication authentication)
            throws IOException {
        OAuth2AuthorizedClient client = coordinator
                .authorizedClient(request, response, authentication)
                .orElseThrow(() -> {
                    SessionInvalidation.invalidate(request);
                    return new SessionRevokedException("The session holds no usable tokens");
                });

        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        URI target = targetUri(request);
        // No size cap on either buffer, deliberately (#247 subtraction cut S2): only invited, admitted
        // users reach this, Tomcat already bounds a request body, and the edge limit is #251's. Do not
        // add a 413 here without reopening that decision.
        byte[] body = shouldReadRequestBody(method) ? request.getInputStream().readAllBytes() : new byte[0];

        RestClient.RequestBodySpec spec = backend.method(method)
                .uri(target)
                .headers(headers -> forwardRequestHeaders(request, headers))
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + client.getAccessToken().getTokenValue());
        // Zero bytes means no body: a bodiless POST must not acquire an application/octet-stream one.
        if (body.length > 0) {
            spec.body(body);
        }

        ResponseEntity<byte[]> fromBackend;
        try {
            // Every status passes through, so the handler is a no-op: 403, 404 and 409 are the SPA's to
            // interpret, and the one status we do translate is handled below. toEntity buffers the body.
            fromBackend = spec.retrieve()
                    .onStatus(status -> true, (backendRequest, backendResponse) -> {})
                    .toEntity(byte[].class);
        } catch (RestClientException e) {
            // Method + path only, never headers.
            throw new BackendUnavailableException(
                    "Backend unreachable for " + method + " " + request.getRequestURI(), e);
        }

        if (fromBackend.getStatusCode().value() == HttpStatus.UNAUTHORIZED.value()) {
            log.error(
                    "Backend rejected a gateway-minted token on {} {} (WWW-Authenticate: {})",
                    method,
                    request.getRequestURI(),
                    fromBackend.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
            throw new BackendRejectedGatewayTokenException("The backend rejected the gateway's token");
        }
        writeResponse(response, fromBackend);
    }

    /**
     * Raw path and query, still percent-encoded as the browser sent them. {@code UriComponentsBuilder}
     * would regroup repeated parameters; {@code RestClient.uri(URI)} skips template expansion. Tomcat and
     * the firewall have already rejected {@code ..}, encoded slashes and raw brackets.
     *
     * <p>The catch is not dead: Tomcat does not validate percent-escapes in a query, so {@code ?q=100%}
     * arrives here and {@code URI.create} throws. {@code MalformedTargetTest} pins that against a real
     * container.
     */
    private URI targetUri(HttpServletRequest request) {
        String rawPath = request.getRequestURI().substring(PREFIX.length());
        String rawQuery = request.getQueryString();
        String target = baseUrl + rawPath + (rawQuery == null ? "" : "?" + rawQuery);
        try {
            return URI.create(target);
        } catch (IllegalArgumentException e) {
            throw new InvalidProxyTargetException("Request path or query cannot be proxied", e);
        }
    }

    private static boolean shouldReadRequestBody(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PATCH;
    }

    /**
     * Only these three, plus the bearer added by the caller. Never {@code Cookie}, a browser-sent
     * {@code Authorization}, {@code X-XSRF-TOKEN}, {@code Host}, {@code Content-Length} (restricted in the
     * JDK client; the byte array sizes the request) or hop-by-hop headers.
     */
    private static void forwardRequestHeaders(HttpServletRequest request, HttpHeaders headers) {
        copyIfPresent(request, headers, HttpHeaders.CONTENT_TYPE);
        copyIfPresent(request, headers, HttpHeaders.ACCEPT);
        // The BFF's own filter minted or validated this one, so both log lines share it.
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            headers.set(CorrelationIdFilter.HEADER, correlationId);
        }
    }

    private static void copyIfPresent(HttpServletRequest request, HttpHeaders headers, String name) {
        String value = request.getHeader(name);
        if (value != null) {
            headers.set(name, value);
        }
    }

    /** Status, {@code Content-Type} and the correlation id only: everything else stays the BFF's own. */
    private static void writeResponse(HttpServletResponse response, ResponseEntity<byte[]> fromBackend) {
        response.setStatus(fromBackend.getStatusCode().value());
        copyBack(response, fromBackend, HttpHeaders.CONTENT_TYPE);
        copyBack(response, fromBackend, CorrelationIdFilter.HEADER);
        byte[] body = fromBackend.getBody();
        if (body == null) {
            return;
        }
        try {
            response.getOutputStream().write(body);
        } catch (IOException e) {
            // The browser went away before the buffered response could be written; nothing to do.
            log.debug("Client aborted while writing the proxied response: {}", e.getMessage());
        }
    }

    private static void copyBack(HttpServletResponse response, ResponseEntity<byte[]> fromBackend, String name) {
        String value = fromBackend.getHeaders().getFirst(name);
        if (value != null) {
            response.setHeader(name, value);
        }
    }
}
