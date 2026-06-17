package com.np.pricehunt.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
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

    @Test
    void restClientException_supertype_isHandled() {
        // Sanity: a generic RestClientException (not a timeout subtype) still routes to the handler.
        RestClientException ex = new RestClientException("downstream failed");
        assertThat(handler.handleScraperFailure(ex).getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
    }
}
