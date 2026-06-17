package com.np.pricehunt.backend.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.http.HttpStatus;
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

    // Ollama failures surface as Spring AI's Transient (5xx) / NonTransient (4xx) AiException.
    // Neither extends RestClientException, so map both here — we are a gateway to the LLM, an
    // upstream failure is 502, not 500.
    @ExceptionHandler({TransientAiException.class, NonTransientAiException.class})
    public ResponseEntity<Void> handleAiServiceFailure(RuntimeException ex) {
        log.warn("LLM service failure: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
}
