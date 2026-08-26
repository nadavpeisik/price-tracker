package com.np.pricehunt.backend.exception;

/**
 * Base for extraction-pipeline failures, so the scrape-attempt recorder (issue #131) can read an
 * optional {@link ExtractionFailureContext} off the thrown exception without each call site knowing
 * the concrete type. The controller boundary maps every subtype to 502: the backend is a gateway to
 * the public web and to the LLM, and these are upstream failures, not the client's request.
 *
 * <p>The context is the hybrid design's "failure-only context": it carries the <em>actual</em> model
 * + prompt version for a failure where an LLM ran (so an escalated SNIPPET attributes to the heavy
 * model), and an explicit context with {@code null} model for a failure where no model ran
 * (EMPTY_INPUT) — telling the recorder "no model ran" rather than letting it guess a nominal one.
 * {@code null} context means "the recorder decides" (BLOCKED, or an unexpected non-LLM throwable).
 */
public class PriceExtractionException extends ApplicationException {

    /** Model attribution for a failure where the LLM call is identifiable. {@code null} fields = no model ran. */
    public record ExtractionFailureContext(String modelName, String promptVersion) {}

    // transient: Throwable is Serializable; the context is in-process audit metadata only.
    private final transient ExtractionFailureContext context;

    protected PriceExtractionException(String message, ExtractionFailureContext context) {
        super(message);
        this.context = context;
    }

    protected PriceExtractionException(String message, Throwable cause, ExtractionFailureContext context) {
        super(message, cause);
        this.context = context;
    }

    /** The model attribution attached at the throw site, or {@code null} if none was attached. */
    public ExtractionFailureContext getContext() {
        return context;
    }
}
