package com.np.pricehunt.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
        name = "scheduled_job_run",
        indexes = {
            @Index(name = "idx_scheduled_job_run_name_started", columnList = "job_name, started_at DESC"),
            @Index(name = "idx_scheduled_job_run_started", columnList = "started_at DESC")
        })
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ScheduledJobRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_name", nullable = false, length = 64)
    private String jobName;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private JobStatus status;

    @Column(name = "items_processed", nullable = false)
    private int itemsProcessed;

    @Column(name = "items_succeeded", nullable = false)
    private int itemsSucceeded;

    @Column(name = "items_failed", nullable = false)
    private int itemsFailed;

    @Column(name = "error_summary", columnDefinition = "text")
    private String errorSummary;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @PrePersist
    protected void onCreate() {
        if (this.startedAt == null) {
            this.startedAt = Instant.now();
        }
    }
}
