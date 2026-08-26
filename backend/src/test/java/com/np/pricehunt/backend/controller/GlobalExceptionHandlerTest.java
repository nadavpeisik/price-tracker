package com.np.pricehunt.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.np.pricehunt.backend.exception.ApplicationException;
import com.np.pricehunt.backend.exception.ConflictException;
import com.np.pricehunt.backend.exception.DependencyTimeoutException;
import com.np.pricehunt.backend.exception.DependencyUnavailableException;
import com.np.pricehunt.backend.exception.ErrorCode;
import com.np.pricehunt.backend.exception.NotFoundException;
import com.np.pricehunt.backend.exception.RefreshCooldownException;
import com.np.pricehunt.backend.exception.ScrapeBlockedException;
import com.np.pricehunt.backend.exception.ValidationException;
import java.sql.SQLException;
import java.util.stream.Stream;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    // --- application exceptions: the only kind → status table in the codebase (#231) ---

    static Stream<Arguments> kindToStatus() {
        return Stream.of(
                Arguments.of(new ValidationException("bad"), HttpStatus.BAD_REQUEST),
                Arguments.of(new NotFoundException("missing"), HttpStatus.NOT_FOUND),
                Arguments.of(
                        new ConflictException(ErrorCode.PRODUCT_LISTING_LIMIT_REACHED, "full"), HttpStatus.CONFLICT),
                Arguments.of(new RefreshCooldownException("slow down"), HttpStatus.TOO_MANY_REQUESTS),
                Arguments.of(new ScrapeBlockedException("cloudflare"), HttpStatus.BAD_GATEWAY),
                Arguments.of(new DependencyUnavailableException("dns down", null), HttpStatus.SERVICE_UNAVAILABLE),
                Arguments.of(new DependencyTimeoutException("dns slow", null), HttpStatus.GATEWAY_TIMEOUT));
    }

    @ParameterizedTest
    @MethodSource("kindToStatus")
    void applicationException_mapsKindToStatus_withMessageAsDetail(ApplicationException ex, HttpStatus expected) {
        ResponseEntity<ProblemDetail> response = handler.handleApplicationException(ex);

        assertThat(response.getStatusCode()).isEqualTo(expected);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStatus()).isEqualTo(expected.value());
        assertThat(response.getBody().getDetail()).isEqualTo(ex.getMessage());
    }

    @Test
    void codedException_emitsErrorCodeMember() {
        var response = handler.handleApplicationException(
                new ConflictException(ErrorCode.URL_TRACKED_BY_ANOTHER_PRODUCT, "taken"));

        assertThat(response.getBody().getProperties())
                .containsEntry(GlobalExceptionHandler.ERROR_CODE_MEMBER, "URL_TRACKED_BY_ANOTHER_PRODUCT");
    }

    @Test
    void unmappedSubtype_failsLoudly_ratherThanGuessingAStatus() {
        ApplicationException stray = new ApplicationException("new kind nobody mapped") {};

        assertThatThrownBy(() -> handler.handleApplicationException(stray))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Unmapped");
    }

    // --- unique-constraint race backstop (#232): the same shape as a thrown ConflictException ---

    // The shape Spring's HibernateJpaDialect produces: DataIntegrityViolationException wrapping
    // Hibernate's ConstraintViolationException wrapping the driver's SQLException.
    private static DataIntegrityViolationException integrityViolation(String constraint, String sqlState) {
        SQLException sql = new SQLException("violates constraint " + constraint, sqlState);
        return new DataIntegrityViolationException(
                "could not execute statement", new ConstraintViolationException("dup", sql, constraint));
    }

    @Test
    void productNameUniqueViolation_mapsTo409WithReadableDetailAndCode() {
        var response = handler.handleUniqueViolation(integrityViolation("uq_product_name_ci", "23505"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("A product with that name already exists");
        assertThat(response.getBody().getProperties())
                .containsEntry(GlobalExceptionHandler.ERROR_CODE_MEMBER, "PRODUCT_NAME_ALREADY_EXISTS");
    }

    @Test
    void unlistedUniqueConstraint_isRethrown_soAServerBugStays500() {
        DataIntegrityViolationException ex = integrityViolation("some_internal_unique_idx", "23505");
        assertThatThrownBy(() -> handler.handleUniqueViolation(ex)).isSameAs(ex);
    }

    @Test
    void nonUniqueIntegrityViolation_isRethrown() {
        // A NOT NULL (23502) or foreign-key (23503) failure is a server bug, not a client conflict —
        // even one whose constraint happens to carry a listed name.
        DataIntegrityViolationException ex = integrityViolation("uq_product_name_ci", "23502");
        assertThatThrownBy(() -> handler.handleUniqueViolation(ex)).isSameAs(ex);
    }

    // --- gateway failures ---

    @Test
    void restClientException_mapsTo502() {
        // ResourceAccessException (Ollama/scraper timeout) is a RestClientException — documents the
        // existing contract that an LLM read-timeout fails the interactive call with 502, not 500.
        ResponseEntity<Void> response = handler.handleScraperFailure(new ResourceAccessException("timeout"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void restClientException_supertype_isHandled() {
        // Sanity: a generic RestClientException (not a timeout subtype) still routes to the handler.
        RestClientException ex = new RestClientException("downstream failed");
        assertThat(handler.handleScraperFailure(ex).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void transientAiException_mapsTo502() {
        ResponseEntity<Void> response = handler.handleAiServiceFailure(new TransientAiException("ollama 503"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    void nonTransientAiException_mapsTo502() {
        ResponseEntity<Void> response = handler.handleAiServiceFailure(new NonTransientAiException("ollama 400"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
