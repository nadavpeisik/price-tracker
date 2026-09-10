package com.np.pricehunt.bff.controller;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.bff.exception.BackendRejectedGatewayTokenException;
import com.np.pricehunt.bff.exception.BackendUnavailableException;
import com.np.pricehunt.bff.exception.IdentityProviderUnavailableException;
import com.np.pricehunt.bff.exception.InvalidProxyTargetException;
import com.np.pricehunt.bff.exception.SessionRevokedException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.web.csrf.MissingCsrfTokenException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Kind of failure to HTTP status, the one mapping in the module. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void everyKind_hasItsStatus() {
        assertThat(GlobalExceptionHandler.httpStatusFor(new InvalidProxyTargetException("m", null)))
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(GlobalExceptionHandler.httpStatusFor(new SessionRevokedException("m")))
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(GlobalExceptionHandler.httpStatusFor(new BackendUnavailableException("m", null)))
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(GlobalExceptionHandler.httpStatusFor(new BackendRejectedGatewayTokenException("m")))
                .isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(GlobalExceptionHandler.httpStatusFor(new IdentityProviderUnavailableException("m", null)))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    void bffException_bodyIsProblemDetailWithTheMessage() {
        ResponseEntity<ProblemDetail> response = handler.handleBffException(new SessionRevokedException("gone"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().getDetail()).isEqualTo("gone");
        assertThat(response.getBody().getStatus()).isEqualTo(401);
    }

    @Test
    void securityRejections() {
        assertThat(handler.handleAuthenticationFailure(new InsufficientAuthenticationException("x"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(handler.handleAccessDenied(new MissingCsrfTokenException("t"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void routingErrors_keepTheirOwnStatus() {
        assertThat(handler.handleRoutingError(
                                new NoResourceFoundException(org.springframework.http.HttpMethod.GET, "/", "/"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(handler.handleRoutingError(new HttpRequestMethodNotSupportedException("PUT"))
                        .getStatusCode())
                .isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
    }
}
