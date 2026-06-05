package com.np.pricehunt.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
// Partial index on (job_run_id) WHERE status='FAILED' is declared in V6 migration
// only — JPA @Index cannot express predicate. Hibernate ddl-auto=validate ignores
// indexes, so the divergence is fine.
@Table(
        name = "scheduled_job_run_item",
        indexes = {@Index(name = "idx_scheduled_job_run_item_run", columnList = "job_run_id")})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledJobRunItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_run_id", nullable = false)
    private ScheduledJobRun run;

    @Column(nullable = false, columnDefinition = "text")
    private String label;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobStatus status;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;
}
