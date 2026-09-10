package com.np.pricehunt.bff.controller;

import com.np.pricehunt.bff.exception.BackendRejectedGatewayTokenException;
import com.np.pricehunt.bff.exception.BackendUnavailableException;
import com.np.pricehunt.bff.exception.BffException;
import com.np.pricehunt.bff.exception.IdentityProviderUnavailableException;
import com.np.pricehunt.bff.exception.InvalidProxyTargetException;
import com.np.pricehunt.bff.exception.SessionRevokedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * The one place HTTP status is decided and the only writer of {@code ProblemDetail} bodies (the
 * backend's #231 contract). {@link BffException} is sealed, so the switch below is exhaustive: a new
 * kind without a status does not compile. Anything else that is not a Spring Security rejection or a
 * routing error stays a 500 rather than a guess.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BffException.class)
    public ResponseEntity<ProblemDetail> handleBffException(BffException ex) {
        HttpStatus status = httpStatusFor(ex);
        if (status.is5xxServerError()) {
            log.warn("{}: {}", ex.getClass().getSimpleName(), ex.getMessage(), ex);
        } else {
            log.info("{}: {}", ex.getClass().getSimpleName(), ex.getMessage());
        }
        return problemResponse(status, ex.getMessage());
    }

    static HttpStatus httpStatusFor(BffException ex) {
        return switch (ex) {
            case InvalidProxyTargetException e -> HttpStatus.BAD_REQUEST;
            case SessionRevokedException e -> HttpStatus.UNAUTHORIZED;
            case BackendUnavailableException e -> HttpStatus.BAD_GATEWAY;
            case BackendRejectedGatewayTokenException e -> HttpStatus.BAD_GATEWAY;
            case IdentityProviderUnavailableException e -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }

    // --- Spring Security rejections, routed here by SecurityRejectionRouter ---

    /** No session, or one the policy filter just ended. The SPA decides when to go to /bff/login. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ProblemDetail> handleAuthenticationFailure(AuthenticationException ex) {
        log.debug("Rejected unauthenticated request: {}", ex.getMessage());
        return problemResponse(HttpStatus.UNAUTHORIZED, "Authentication required");
    }

    /** A missing or wrong CSRF token on a mutation. */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccessDenied(AccessDeniedException ex) {
        log.info("Rejected request: {}", ex.getMessage());
        return problemResponse(HttpStatus.FORBIDDEN, ex.getMessage());
    }

    // --- MVC routing errors: the standalone BFF serves nothing at /, so a logged-in browser landing
    // there gets a 404 ProblemDetail rather than a container error page (an ERROR dispatch that the
    // security chain would answer 401). ---

    // Every type listed here implements ErrorResponse and carries its own status. Do not add one that
    // does not: MethodArgumentTypeMismatchException was here once and the cast threw, which cost the
    // response its ProblemDetail body and left Spring's fallback resolver to answer instead.
    @ExceptionHandler({
        NoResourceFoundException.class,
        HttpRequestMethodNotSupportedException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ProblemDetail> handleRoutingError(ErrorResponse error) {
        HttpStatus status = HttpStatus.valueOf(error.getStatusCode().value());
        return problemResponse(status, status.getReasonPhrase());
    }

    /** A parameter that will not convert, such as {@code /bff/login?remember=maybe}. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleUnconvertibleParameter(MethodArgumentTypeMismatchException ex) {
        log.debug("Rejected unconvertible parameter '{}': {}", ex.getName(), ex.getMessage());
        return problemResponse(HttpStatus.BAD_REQUEST, "Parameter '" + ex.getName() + "' is not a valid value");
    }

    private static ResponseEntity<ProblemDetail> problemResponse(HttpStatus status, String detail) {
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, detail));
    }
}
