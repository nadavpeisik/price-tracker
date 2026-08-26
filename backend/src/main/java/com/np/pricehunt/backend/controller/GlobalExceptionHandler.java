package com.np.pricehunt.backend.controller;

import java.util.Map;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.RestClientException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(RestClientException.class)
    public ResponseEntity<Void> handleScraperFailure(RestClientException ex) {
        log.warn("Downstream service call failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }

    /**
     * Unique constraints the client can act on, by index name → the 409 detail. The database is the
     * only place uniqueness holds under concurrent writes, so the service does no pre-check; this
     * is where a duplicate becomes a conflict the client can read. A unique violation from an index
     * NOT listed here is a server bug and stays a 500, as do NOT NULL / foreign-key / CHECK
     * failures.
     */
    private static final Map<String, String> CLIENT_FACING_UNIQUE_CONSTRAINTS =
            Map.of("uq_product_name_ci", "A product with that name already exists");

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> handleUniqueViolation(DataIntegrityViolationException ex) {
        String constraint = violatedConstraint(ex).orElse(null);
        String detail = constraint == null ? null : CLIENT_FACING_UNIQUE_CONSTRAINTS.get(constraint);
        if (detail == null) {
            throw ex;
        }
        // The constraint name only: the driver's message carries the user-supplied value.
        log.info("Write rejected by unique constraint {}", constraint);
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, detail));
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
