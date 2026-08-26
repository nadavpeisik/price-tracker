package com.np.pricehunt.backend.service;

import com.np.pricehunt.backend.domain.AvailabilityStatus;
import com.np.pricehunt.backend.domain.ScrapeAttempt;
import com.np.pricehunt.backend.exception.NotFoundException;
import com.np.pricehunt.backend.exception.ValidationException;
import com.np.pricehunt.backend.repository.ScrapeAttemptRepository;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Builds a draft {@code availability-cases.json} fixture from a stored scrape-attempt's exact LLM input
 * (issue #131) — the lookup + assembly behind {@code ScrapeAttemptExportController}, so that controller
 * stays HTTP mapping only (Controller → Service → Repository). Ungated: it exposes nothing on its own;
 * the security gating that matters lives on the controller (the HTTP surface).
 */
@Service
@RequiredArgsConstructor
public class ScrapeAttemptExportService {

    private final ScrapeAttemptRepository repository;

    /** A draft fixture entry in the {@code availability-cases.json} schema (human labels {@code expectedAvailability}). */
    public record FixtureDraft(String name, String text, AvailabilityStatus expectedAvailability) {}

    /**
     * Promotes attempt {@code id}'s LLM input into a draft fixture. Throws {@code 404} when the attempt
     * is absent, and {@code 400} when it has no LLM input (BLOCKED/STRUCTURED/empty — a null-text fixture
     * would be useless and would fail the regression IT).
     */
    public FixtureDraft draftFor(Long id) {
        ScrapeAttempt attempt =
                repository.findById(id).orElseThrow(() -> new NotFoundException("Scrape attempt not found"));
        if (attempt.getLlmInput() == null || attempt.getLlmInput().isBlank()) {
            throw new ValidationException(
                    "Attempt has no LLM input to export (source=" + attempt.getExtractionSource() + ")");
        }
        String name = attempt.getFailureCode().name().toLowerCase(Locale.ROOT) + "_" + attempt.getId();
        return new FixtureDraft(name, attempt.getLlmInput(), null);
    }
}
