package com.np.pricehunt.backend.controller;

import com.np.pricehunt.backend.exception.ApplicationException;
import com.np.pricehunt.backend.exception.ConflictException;
import com.np.pricehunt.backend.exception.DependencyTimeoutException;
import com.np.pricehunt.backend.exception.DependencyUnavailableException;
import com.np.pricehunt.backend.exception.ErrorCode;
import com.np.pricehunt.backend.exception.ForbiddenException;
import com.np.pricehunt.backend.exception.NotFoundException;
import com.np.pricehunt.backend.exception.PriceExtractionException;
import com.np.pricehunt.backend.exception.RefreshCooldownException;
import com.np.pricehunt.backend.exception.ValidationException;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenError;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

/**
 * The one place HTTP status is decided (issue #231). Services throw an {@link ApplicationException}
 * subtype naming the kind of failure; this advice maps kind → status and writes the ProblemDetail,
 * adding the {@code errorCode} member when the exception carries one (issue #173).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** Extension member carrying {@link ErrorCode}; the human {@code detail} is not a contract. */
    static final String ERROR_CODE_MEMBER = "errorCode";

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<ProblemDetail> handleApplicationException(ApplicationException ex) {
        HttpStatus status = httpStatusFor(ex);
        if (status.is5xxServerError()) {
            log.warn("{}: {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        }
        return problemResponse(status, ex);
    }

    // Exhaustive over the hierarchy on purpose: a new subtype with no mapping is a bug that must
    // surface as a 500 here, not be guessed at.
    private static HttpStatus httpStatusFor(ApplicationException ex) {
        return switch (ex) {
            case ValidationException e -> HttpStatus.BAD_REQUEST;
            case NotFoundException e -> HttpStatus.NOT_FOUND;
            case ConflictException e -> HttpStatus.CONFLICT;
            case ForbiddenException e -> HttpStatus.FORBIDDEN;
            case RefreshCooldownException e -> HttpStatus.TOO_MANY_REQUESTS;
            // Gateway to the public web and the LLM: an upstream failure is 502, not 500.
            case PriceExtractionException e -> HttpStatus.BAD_GATEWAY;
            case DependencyUnavailableException e -> HttpStatus.SERVICE_UNAVAILABLE;
            case DependencyTimeoutException e -> HttpStatus.GATEWAY_TIMEOUT;
            default -> throw new IllegalStateException("Unmapped application exception: " + ex.getClass(), ex);
        };
    }

    private static ResponseEntity<ProblemDetail> problemResponse(HttpStatus status, ApplicationException ex) {
        ProblemDetail body = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
        // One coded kind today. When a second kind carries a code, give both a capability interface
        // (e.g. CodedApplicationException) and test that here — not a chain of instanceofs, and not an
        // Optional<ErrorCode> on the root.
        if (ex instanceof ConflictException conflict) {
            body.setProperty(ERROR_CODE_MEMBER, conflict.errorCode().name());
        }
        return ResponseEntity.status(status).body(body);
    }

    // --- Spring Security rejections, routed here by SecurityRejectionRouter (issue #245) ---

    /**
     * The identity provider is unreachable: {@code JwtAuthenticationProvider} wraps every
     * non-malformed-token {@code JwtException}, a failed JWKS fetch included, in this type. A 503 and
     * not a 401, which the BFF would read as "bad credentials" and answer with a re-login loop lasting
     * as long as the outage.
     */
    @ExceptionHandler(AuthenticationServiceException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationServiceFailure(AuthenticationServiceException ex) {
        log.warn("Authentication service unavailable: {}", ex.getMessage(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ProblemDetail.forStatusAndDetail(
                        HttpStatus.SERVICE_UNAVAILABLE, "Authentication service unavailable"));
    }

    /** No or invalid bearer token. Carries the RFC 6750 challenge Spring's default entry point sends. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationFailure(AuthenticationException ex) {
        log.debug("Rejected unauthenticated request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.WWW_AUTHENTICATE, bearerChallenge(ex))
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required"));
    }

    /**
     * Authenticated but not permitted: a missing role, or an identity with no account here. RFC 6750
     * wants a challenge whenever the token does not enable access, and this is the one Spring's
     * {@code BearerTokenAccessDeniedHandler} sends.
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        log.info("Rejected request: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header(
                        HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer error=\"insufficient_scope\", error_description=\"The request requires higher"
                                + " privileges than provided by the access token.\"")
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Not permitted"));
    }

    /**
     * A rejected token's own code and description are reproduced verbatim: they are what Spring's
     * default entry point would have sent, so routing 401s through this advice discloses nothing new.
     */
    private static String bearerChallenge(AuthenticationException ex) {
        if (ex instanceof InsufficientAuthenticationException) {
            return "Bearer";
        }
        if (ex instanceof OAuth2AuthenticationException oauth && oauth.getError() instanceof BearerTokenError error) {
            StringBuilder challenge = new StringBuilder("Bearer error=\"")
                    .append(error.getErrorCode())
                    .append('"');
            if (error.getDescription() != null) {
                challenge
                        .append(", error_description=\"")
                        .append(error.getDescription())
                        .append('"');
            }
            return challenge.toString();
        }
        return "Bearer error=\"invalid_token\"";
    }

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Void> handleScraperFailure(RestClientException ex) {
        log.warn("Downstream service call failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }

    /**
     * Unique constraints the client can act on, by index name → the conflict it means. The database is
     * the only place uniqueness holds under concurrent writes, so the service does no pre-check; this
     * is where a duplicate becomes a conflict the client can read — through the same
     * {@link ConflictException} shape as every other 409, so a race and a rule read identically. A
     * unique violation from an index NOT listed here is a server bug and stays a 500, as do NOT NULL
     * / foreign-key / CHECK failures.
     */
    private static final Map<String, Supplier<ConflictException>> CLIENT_FACING_UNIQUE_CONSTRAINTS = Map.of(
            "uq_product_name_ci",
            () -> new ConflictException(
                    ErrorCode.PRODUCT_NAME_ALREADY_EXISTS, "A product with that name already exists"));

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleUniqueViolation(DataIntegrityViolationException ex) {
        String constraint = violatedConstraint(ex).orElse(null);
        Supplier<ConflictException> conflict =
                constraint == null ? null : CLIENT_FACING_UNIQUE_CONSTRAINTS.get(constraint);
        if (conflict == null) {
            throw ex;
        }
        // The constraint name only: the driver's message carries the user-supplied value.
        log.info("Write rejected by unique constraint {}", constraint);
        return problemResponse(HttpStatus.CONFLICT, conflict.get());
    }

    /**
     * The violated constraint's name, from Hibernate's wrapper in the cause chain. SQLSTATE 23505
     * (unique_violation) is checked as well so a same-named non-unique constraint can never match.
     */
    private static Optional<String> violatedConstraint(DataIntegrityViolationException ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException cve) {
                boolean unique = cve.getSQLException() != null
                        && "23505".equals(cve.getSQLException().getSQLState());
                return unique ? Optional.ofNullable(cve.getConstraintName()) : Optional.empty();
            }
        }
        return Optional.empty();
    }

    // Ollama failures surface as Spring AI's Transient (5xx) / NonTransient (4xx) AiException.
    // Neither extends RestClientException, so map both here — we are a gateway to the LLM, an
    // upstream failure is 502, not 500.
    @ExceptionHandler({TransientAiException.class, NonTransientAiException.class})
    public ResponseEntity<Void> handleAiServiceFailure(RuntimeException ex) {
        log.warn("LLM service failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
}
