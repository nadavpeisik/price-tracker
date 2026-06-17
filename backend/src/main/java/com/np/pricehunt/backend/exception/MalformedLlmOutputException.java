package com.np.pricehunt.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Thrown by OllamaPriceExtractionService when the LLM output couldn't be parsed by the
// structured-output converter. A dedicated type lets the orchestrator escalate to the heavier
// model only on genuine bad output, while transport failures and bugs propagate untouched.
// 502 BAD_GATEWAY (like EmptyExtractionInputException): the upstream LLM payload is malformed.
public class MalformedLlmOutputException extends ResponseStatusException {
    public MalformedLlmOutputException(String model, Throwable cause) {
        super(HttpStatus.BAD_GATEWAY, "LLM returned unparseable output (model=%s)".formatted(model), cause);
    }
}
