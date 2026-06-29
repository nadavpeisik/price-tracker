package com.np.pricehunt.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Failure-first scrape-attempt audit row (issue #131): one row per extraction failure or validation
 * rejection — never on success. Captures the exact LLM input + model/prompt so a non-reproducible
 * failure can be replayed (old input → new prompt/model → diff PriceInfo) and mined into the
 * regression corpus.
 *
 * <p><b>{@code @Getter}/{@code @Setter}, deliberately NOT {@code @Data}</b> (a justified deviation from
 * the domain-entity convention): this row uniquely holds large, <em>untrusted</em> page text
 * ({@code llmInput}, {@code failureDetail}). {@code @Data}'s generated {@code toString} would log-leak
 * it, and {@code equals}/{@code hashCode} over it is a perf + identity hazard (the id flips on
 * persist). So no generated {@code toString}/{@code equals}/{@code hashCode} — default object identity.
 *
 * <p><b>No hard FK on {@code trackedItemId}</b> (a plain {@code Long}, not {@code @ManyToOne}): this is
 * an append-only <em>evidence</em> table whose whole point is to survive deletion of the item it came
 * from. A hard FK would fail the best-effort audit insert on a concurrent item delete (losing exactly
 * the failure we want) and null the id on delete (destroying forensic context). The historical id is
 * preserved instead.
 *
 * <p>The two hashes have distinct roles: {@code contentHash} = SHA-256 of the raw scraper evidence (a
 * non-replay dedup/diagnostic hash; null for BLOCKED/STRUCTURED with no text); {@code llmInputHash} =
 * SHA-256 of the stored {@code llmInput} replay text.
 *
 * <p>{@code createdAt}/{@code retentionUntil} are set by {@code ScrapeAttemptRecorder} via the injected
 * {@code Clock} (not {@code @PrePersist}) so both share one clock and are controllable in tests.
 */
@Entity
// Partial index idx_scrape_attempt_llm_input_hash (WHERE llm_input_hash IS NOT NULL) and the DESC
// ordering live in V9 only — JPA @Index can express neither, and ddl-auto=validate ignores indexes.
@Table(
        name = "scrape_attempt",
        indexes = {
            @Index(name = "idx_scrape_attempt_item", columnList = "tracked_item_id"),
            @Index(name = "idx_scrape_attempt_retention", columnList = "retention_until"),
            @Index(name = "idx_scrape_attempt_created_at", columnList = "created_at DESC")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScrapeAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Historical id, NO foreign key — see class Javadoc. Nullable: an attempt can outlive its item.
    @Column(name = "tracked_item_id")
    private Long trackedItemId;

    @Column(nullable = false, columnDefinition = "text")
    private String url;

    @Enumerated(EnumType.STRING)
    @Column(name = "extraction_source", nullable = false, length = 16)
    private ExtractionSource extractionSource;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private ScrapeOutcome outcome;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_code", nullable = false, length = 32)
    private ScrapeFailureCode failureCode;

    @Column(name = "failure_detail", columnDefinition = "text")
    private String failureDetail;

    @Column(name = "llm_input", columnDefinition = "text")
    private String llmInput;

    @Column(name = "prompt_version", length = 32)
    private String promptVersion;

    @Column(name = "model_name", length = 255)
    private String modelName;

    @Column(name = "content_hash", length = 64)
    private String contentHash;

    @Column(name = "llm_input_hash", length = 64)
    private String llmInputHash;

    // Fingerprint of the extraction config (output schema + options) — a separate axis from
    // prompt_version + model_name; null when no LLM ran. See ExtractionConfigFingerprint (#131).
    @Column(name = "extraction_config_hash", length = 64)
    private String extractionConfigHash;

    @Column(name = "correlation_id", length = 255)
    private String correlationId;

    @Column(name = "retention_until", nullable = false)
    private Instant retentionUntil;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
