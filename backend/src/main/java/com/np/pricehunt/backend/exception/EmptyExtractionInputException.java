package com.np.pricehunt.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Thrown when the scraper returned a payload but the text we'd send to the LLM
// is below a minimum threshold (e.g. FULLTEXT with empty innerText). Distinct
// from ScrapeBlockedException because the scraper didn't flag the page as
// blocked — this catches undetected bot walls, broken pages, or scraper bugs.
// 502 BAD_GATEWAY for the same reason as ScrapeBlockedException: backend is a
// gateway to the public web, the upstream payload is malformed/empty, not the
// client's request.
public class EmptyExtractionInputException extends ResponseStatusException {
    public EmptyExtractionInputException(String source, int chars) {
        super(
                HttpStatus.BAD_GATEWAY,
                "Scraper returned insufficient text for LLM extraction (source=%s, chars=%d)".formatted(source, chars));
    }
}
