package com.np.pricehunt.backend.exception;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

// Thrown when the scraper detects an anti-bot wall (e.g. Cloudflare managed
// challenge) and short-circuits with ExtractionSource.BLOCKED. 502 BAD_GATEWAY
// because the backend is acting as a gateway to the public web — the upstream
// site is intentionally refusing us, not the client's payload being malformed.
// 502 also leaves the door open for future scraper retry/cooldown logic without
// clients mistaking the failure for a 4xx validation error.
public class ScrapeBlockedException extends ResponseStatusException {
    public ScrapeBlockedException(String reason) {
        super(HttpStatus.BAD_GATEWAY,
                "Scrape blocked by anti-bot protection (%s)".formatted(reason));
    }
}
