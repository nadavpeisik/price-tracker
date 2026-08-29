package com.np.pricehunt.backend.exception;

// Thrown by LlmPriceExtractionService when the LLM output couldn't be parsed by the
// structured-output converter. A dedicated type lets the orchestrator escalate to the heavier
// model only on genuine bad output, while transport failures and bugs propagate untouched.
// 502 BAD_GATEWAY (like EmptyExtractionInputException): the upstream LLM payload is malformed.
//
// Carries an ExtractionFailureContext with the ACTUAL model + prompt version that produced the
// unparseable output, so the recorder (issue #131) attributes the failure to the exact model —
// including the heavy model when a SNIPPET attempt escalated before failing.
public class MalformedLlmOutputException extends PriceExtractionException {
    public MalformedLlmOutputException(String model, String promptVersion, Throwable cause) {
        super(
                "LLM returned unparseable output (model=%s)".formatted(model),
                cause,
                new ExtractionFailureContext(model, promptVersion));
    }
}
