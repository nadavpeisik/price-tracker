package com.np.pricehunt.backend.observability;

import com.np.pricehunt.backend.config.PriceExtractionProperties;
import com.np.pricehunt.backend.config.ScrapeAuditProperties;
import com.np.pricehunt.backend.domain.ExtractionSource;
import com.np.pricehunt.backend.domain.ScrapeAttempt;
import com.np.pricehunt.backend.domain.ScrapeFailureCode;
import com.np.pricehunt.backend.domain.ScrapeOutcome;
import com.np.pricehunt.backend.dto.ScrapeResponse;
import com.np.pricehunt.backend.exception.EmptyExtractionInputException;
import com.np.pricehunt.backend.exception.MalformedLlmOutputException;
import com.np.pricehunt.backend.exception.PriceExtractionException;
import com.np.pricehunt.backend.exception.PriceExtractionException.ExtractionFailureContext;
import com.np.pricehunt.backend.exception.ScrapeBlockedException;
import com.np.pricehunt.backend.repository.ScrapeAttemptRepository;
import com.np.pricehunt.backend.service.ExtractionConfigFingerprint;
import com.np.pricehunt.backend.service.LlmInputResolver;
import com.np.pricehunt.backend.service.OllamaPriceExtractionService;
import com.np.pricehunt.backend.util.Hashing;
import com.np.pricehunt.backend.util.Throwables;
import com.np.pricehunt.backend.util.UrlSanitizer;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists a failure-first {@code scrape_attempt} row (issue #131). Mirrors {@code JobRunRecorder}: each
 * method is {@code REQUIRES_NEW} so the audit commits independently of the caller's transaction (a
 * failure that rolls back the main work must not roll back the evidence). Callers ({@code
 * ProductTrackingService}) invoke this OUTSIDE any transaction and wrap the call best-effort, so a
 * recorder hiccup can never mask the original failure — there is no internal swallow here.
 *
 * <p>The exact {@code llm_input} is re-derived from the {@code ScrapeResponse} via the shared {@link
 * LlmInputResolver} bean, so it is byte-identical to what the model saw. Model/prompt attribution comes
 * from the throwable's {@link ExtractionFailureContext} when present (exact, incl. an escalated heavy
 * model); otherwise it falls back to the nominal model for the LLM tiers, or null when no model ran.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScrapeAttemptRecorder {

    /** Shared MDC key (matches {@code JobRunRecorder} / the schedulers) for the scheduled-run correlation id. */
    public static final String CORRELATION_ID_MDC_KEY = "correlationId";

    // failure_detail holds untrusted, potentially noisy exception/page text — bound it.
    private static final int MAX_DETAIL_CHARS = 1000;
    // correlation_id column width — bound the MDC value so an over-long id can't fail the audit insert.
    private static final int CORRELATION_ID_MAX_CHARS = 255;

    private final ScrapeAttemptRepository repository;
    private final LlmInputResolver llmInputResolver;
    private final ExtractionConfigFingerprint configFingerprint;
    private final PriceExtractionProperties extractionProperties;
    private final ScrapeAuditProperties auditProperties;
    private final Clock clock;

    /**
     * The "an LLM ran" stamps for one attempt — set together, null together. {@code modelName}/{@code
     * promptVersion} vary per call (exact-from-context vs nominal); {@code extractionConfigHash} is a
     * process-wide startup constant that rides along whenever a model ran (built via {@link #attribution}).
     */
    private record LlmAttribution(String modelName, String promptVersion, String extractionConfigHash) {}

    /** An extraction-pipeline throwable: derive failure code + model attribution from the cause. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordExtractionFailure(Long itemId, String url, ScrapeResponse scraped, Throwable cause) {
        ScrapeFailureCode code = failureCodeFor(cause);
        String detail = (code == ScrapeFailureCode.BLOCKED) ? scraped.blockedReason() : Throwables.summarize(cause);
        save(
                itemId,
                url,
                ScrapeOutcome.EXTRACTION_FAILED,
                code,
                detail,
                scraped,
                modelFor(cause, scraped.extractionSource()));
    }

    /** A produced-but-rejected price: the LLM tiers ran (for SNIPPET/FULLTEXT), so record the nominal model. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordValidationRejection(
            Long itemId, String url, ScrapeResponse scraped, ScrapeFailureCode code, String detail) {
        save(
                itemId,
                url,
                ScrapeOutcome.VALIDATION_REJECTED,
                code,
                detail,
                scraped,
                nominalModelFor(scraped.extractionSource()));
    }

    private void save(
            Long itemId,
            String url,
            ScrapeOutcome outcome,
            ScrapeFailureCode code,
            String detail,
            ScrapeResponse scraped,
            LlmAttribution attribution) {
        // SECURITY (Phase 1.7 / #139 prerequisite): llmInput is the raw scraped page text. UrlValidator
        // does not yet block private-IP / cloud-metadata hosts, so until Phase 1.7 SSRF hardening lands a
        // failed scrape of an internal URL could persist (and dev-export) an internal response body for
        // the retention window. This is the pre-existing SSRF gap amplified into storage — Phase 1.7
        // must precede any cloud / multi-user deploy of this audit (the dev export is already
        // disabled-by-default + dev-profile-gated as a partial mitigation).
        String llmInput = llmInputResolver.resolve(scraped);
        Instant now = Instant.now(clock);
        ScrapeAttempt attempt = ScrapeAttempt.builder()
                .trackedItemId(itemId)
                .url(UrlSanitizer.minimize(url))
                .extractionSource(scraped.extractionSource())
                .outcome(outcome)
                .failureCode(code)
                .failureDetail(truncate(detail, MAX_DETAIL_CHARS))
                .llmInput(llmInput)
                .promptVersion(attribution.promptVersion())
                .modelName(attribution.modelName())
                .extractionConfigHash(attribution.extractionConfigHash())
                .contentHash(Hashing.sha256Hex(rawEvidence(scraped)))
                .llmInputHash(Hashing.sha256Hex(llmInput))
                .correlationId(truncate(MDC.get(CORRELATION_ID_MDC_KEY), CORRELATION_ID_MAX_CHARS))
                .retentionUntil(now.plus(auditProperties.retention()))
                .createdAt(now)
                .build();
        repository.save(attempt);
        log.debug("Recorded scrape_attempt outcome={} code={} source={}", outcome, code, scraped.extractionSource());
    }

    private static ScrapeFailureCode failureCodeFor(Throwable cause) {
        if (cause instanceof ScrapeBlockedException) return ScrapeFailureCode.BLOCKED;
        if (cause instanceof EmptyExtractionInputException) return ScrapeFailureCode.EMPTY_INPUT;
        if (cause instanceof MalformedLlmOutputException) return ScrapeFailureCode.MALFORMED_LLM_OUTPUT;
        return ScrapeFailureCode.EXTRACTION_ERROR;
    }

    // Model attribution. A context attached to the throwable is authoritative: MalformedLlmOutput
    // carries the actual (possibly escalated) model; EmptyExtractionInput carries (null, null) =
    // "no model ran". Absent a context, we fall back to the nominal model for the LLM tiers (an
    // unexpected non-LLM bug on a SNIPPET/FULLTEXT page), or null for STRUCTURED/BLOCKED.
    private LlmAttribution modelFor(Throwable cause, ExtractionSource source) {
        if (cause instanceof PriceExtractionException pee && pee.getContext() != null) {
            ExtractionFailureContext ctx = pee.getContext();
            return attribution(ctx.modelName(), ctx.promptVersion());
        }
        return nominalModelFor(source);
    }

    private LlmAttribution nominalModelFor(ExtractionSource source) {
        return switch (source) {
            case SNIPPET ->
                attribution(extractionProperties.snippetModel(), OllamaPriceExtractionService.PROMPT_VERSION);
            case FULLTEXT ->
                attribution(extractionProperties.fulltextModel(), OllamaPriceExtractionService.PROMPT_VERSION);
            case STRUCTURED, BLOCKED -> attribution(null, null);
        };
    }

    // Centralizes the "config hash is set iff an LLM ran" rule (promptVersion != null == a model ran), so
    // the three stamps can't desync. The hash is a startup constant read from the singleton fingerprint —
    // only model/prompt vary per attempt — so it rides along here, not the per-call exception context.
    private LlmAttribution attribution(String modelName, String promptVersion) {
        return new LlmAttribution(
                modelName, promptVersion, promptVersion != null ? configFingerprint.getExtractionConfigHash() : null);
    }

    // content_hash hashes the RAW scraper evidence (not the filtered/capped llm_input), so it dedups by
    // page content. Null for STRUCTURED/BLOCKED (no text) -> Hashing yields a null hash.
    private static String rawEvidence(ScrapeResponse scraped) {
        return switch (scraped.extractionSource()) {
            case SNIPPET -> scraped.snippet();
            case FULLTEXT -> scraped.innerText();
            case STRUCTURED, BLOCKED -> null;
        };
    }

    // Bounds a value to its column width before the audit write, so an over-long input can never throw a
    // truncation error and lose the (best-effort) row. Null-safe.
    private static String truncate(String value, int maxChars) {
        if (value == null || value.length() <= maxChars) {
            return value;
        }
        return value.substring(0, maxChars);
    }
}
