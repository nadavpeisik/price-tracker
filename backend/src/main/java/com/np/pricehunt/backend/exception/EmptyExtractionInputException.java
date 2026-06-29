package com.np.pricehunt.backend.exception;

import org.springframework.http.HttpStatus;

// Thrown when the scraper returned a payload but the text we'd send to the LLM
// is below a minimum threshold (e.g. FULLTEXT with empty innerText). Distinct
// from ScrapeBlockedException because the scraper didn't flag the page as
// blocked — this catches undetected bot walls, broken pages, or scraper bugs.
// 502 BAD_GATEWAY for the same reason as ScrapeBlockedException: backend is a
// gateway to the public web, the upstream payload is malformed/empty, not the
// client's request.
//
// Carries an ExtractionFailureContext with a NULL model: the min-length guard fires
// BEFORE any LLM call, so no model ran. The explicit (null) context tells the recorder
// (issue #131) "no model ran" — so it records a null model instead of guessing a nominal one.
public class EmptyExtractionInputException extends PriceExtractionException {
    public EmptyExtractionInputException(String source, int chars) {
        super(
                HttpStatus.BAD_GATEWAY,
                "Scraper returned insufficient text for LLM extraction (source=%s, chars=%d)".formatted(source, chars),
                new ExtractionFailureContext(null, null));
    }
}
