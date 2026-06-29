package com.np.pricehunt.backend.controller.dev;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ScrapeAttempt;
import com.np.pricehunt.backend.repository.ScrapeAttemptRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Dev-only: promotes a stored scrape-attempt's exact LLM input into a <em>draft</em>
 * {@code availability-cases.json} fixture (issue #131), so a real production failure can become a
 * regression case.
 *
 * <p><b>Double-gated</b> — {@code @Profile("dev")} AND {@code scrape.audit.export-enabled=true}
 * (disabled by default). There is no Spring Security in this app and the endpoint returns untrusted
 * raw page text, so a single mistaken {@code dev} profile must not be enough to expose the corpus.
 *
 * <p>The fixture is a <b>draft</b>: {@code expectedAvailability} is left {@code null} for a human to
 * label before it's appended to {@code availability-cases.json} (a null label would fail the
 * regression IT). Only the three fields the regression reader accepts are emitted.
 */
@RestController
@RequestMapping("/api/dev/scrape-attempts")
@Profile("dev")
@ConditionalOnProperty(name = "scrape.audit.export-enabled", havingValue = "true")
@RequiredArgsConstructor
public class ScrapeAttemptExportController {

    private final ScrapeAttemptRepository repository;

    /** A draft fixture entry in the {@code availability-cases.json} schema (human labels {@code expectedAvailability}). */
    public record FixtureDraft(String name, String text, AvailabilityStatus expectedAvailability) {}

    @GetMapping("/{id}/fixture")
    public FixtureDraft fixture(@PathVariable Long id) {
        ScrapeAttempt attempt = repository
                .findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Scrape attempt not found"));
        if (attempt.getLlmInput() == null || attempt.getLlmInput().isBlank()) {
            // BLOCKED/STRUCTURED/empty attempts have no LLM input — a null-text fixture is useless.
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Attempt has no LLM input to export (source=" + attempt.getExtractionSource() + ")");
        }
        String name = attempt.getFailureCode().name().toLowerCase(Locale.ROOT) + "_" + attempt.getId();
        return new FixtureDraft(name, attempt.getLlmInput(), null);
    }
}
