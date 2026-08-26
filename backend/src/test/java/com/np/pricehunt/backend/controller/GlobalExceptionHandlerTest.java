package com.np.pricehunt.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void restClientException_mapsTo502() {
        // ResourceAccessException (Ollama/scraper timeout) is a RestClientException — documents the
        // existing contract that an LLM read-timeout fails the interactive call with 502, not 500.
        ResponseEntity<Void> response = handler.handleScraperFailure(new ResourceAccessException("timeout"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
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

    // The shape Spring's HibernateJpaDialect produces: DataIntegrityViolationException wrapping
    // Hibernate's ConstraintViolationException wrapping the driver's SQLException.
    private static DataIntegrityViolationException integrityViolation(String constraint, String sqlState) {
        SQLException sql = new SQLException("violates constraint " + constraint, sqlState);
        return new DataIntegrityViolationException(
                "could not execute statement", new ConstraintViolationException("dup", sql, constraint));
    }

    @Test
    void productNameUniqueViolation_mapsTo409WithReadableDetail() {
        var response = handler.handleUniqueViolation(integrityViolation("uq_product_name_ci", "23505"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getDetail()).isEqualTo("A product with that name already exists");
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

    @Test
    void restClientException_supertype_isHandled() {
        // Sanity: a generic RestClientException (not a timeout subtype) still routes to the handler.
        RestClientException ex = new RestClientException("downstream failed");
        assertThat(handler.handleScraperFailure(ex).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
