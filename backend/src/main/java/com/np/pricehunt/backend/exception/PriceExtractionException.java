package com.np.pricehunt.backend.exception;

import org.springframework.http.HttpStatusCode;
import org.springframework.web.server.ResponseStatusException;

/**
 * Base for extraction-pipeline failures, so the scrape-attempt recorder (issue #131) can read an
 * optional {@link ExtractionFailureContext} off the thrown exception without each call site knowing
 * the concrete type. Still a {@link ResponseStatusException} — the controller boundary maps it to the
 * same HTTP status as before (502 for all three subtypes).
 *
 * <p>The context is the hybrid design's "failure-only context": it carries the <em>actual</em> model
 * + prompt version for a failure where an LLM ran (so an escalated SNIPPET attributes to the heavy
 * model), and an explicit context with {@code null} model for a failure where no model ran
 * (EMPTY_INPUT) — telling the recorder "no model ran" rather than letting it guess a nominal one.
 * {@code null} context means "the recorder decides" (BLOCKED, or an unexpected non-LLM throwable).
 */
public class PriceExtractionException extends ResponseStatusException {

    /** Model attribution for a failure where the LLM call is identifiable. {@code null} fields = no model ran. */
    public record ExtractionFailureContext(String modelName, String promptVersion) {}

    // transient: ResponseStatusException is Serializable; the context is in-process audit metadata only.
    private final transient ExtractionFailureContext context;

    protected PriceExtractionException(HttpStatusCode status, String reason, ExtractionFailureContext context) {
        super(status, reason);
        this.context = context;
    }

    protected PriceExtractionException(
            HttpStatusCode status, String reason, Throwable cause, ExtractionFailureContext context) {
        super(status, reason, cause);
        this.context = context;
    }

    /** The model attribution attached at the throw site, or {@code null} if none was attached. */
    public ExtractionFailureContext getContext() {
        return context;
    }
}
