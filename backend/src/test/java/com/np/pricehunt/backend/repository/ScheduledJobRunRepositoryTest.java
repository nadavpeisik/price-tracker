package com.np.pricehunt.backend.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.np.pricehunt.backend.domain.JobStatus;
import com.np.pricehunt.backend.domain.ScheduledJobRun;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.test.context.ActiveProfiles;

@DataJpaTest
@ActiveProfiles("test")
class ScheduledJobRunRepositoryTest {

    @Autowired
    private TestEntityManager em;

    @Autowired
    private ScheduledJobRunRepository repository;

    @Test
    void findTop50ByOrderByStartedAtDesc_returnsNewestFirst() {
        Instant t1 = Instant.parse("2026-01-01T12:00:00Z");
        Instant t2 = Instant.parse("2026-02-01T12:00:00Z");
        Instant t3 = Instant.parse("2026-03-01T12:00:00Z");

        em.persist(run("PRICE_REFRESH", t1, JobStatus.SUCCESS));
        em.persist(run("FX_REFRESH", t2, JobStatus.PARTIAL));
        em.persist(run("PRICE_REFRESH", t3, JobStatus.FAILED));
        em.flush();

        List<ScheduledJobRun> results = repository.findTop50ByOrderByStartedAtDesc();

        assertThat(results).hasSize(3);
        assertThat(results.get(0).getStartedAt()).isEqualTo(t3);
        assertThat(results.get(1).getStartedAt()).isEqualTo(t2);
        assertThat(results.get(2).getStartedAt()).isEqualTo(t1);
    }

    @Test
    void findTop50ByJobNameOrderByStartedAtDesc_filtersByJobName() {
        Instant t1 = Instant.parse("2026-01-01T12:00:00Z");
        Instant t2 = Instant.parse("2026-02-01T12:00:00Z");
        Instant t3 = Instant.parse("2026-03-01T12:00:00Z");

        em.persist(run("PRICE_REFRESH", t1, JobStatus.SUCCESS));
        em.persist(run("FX_REFRESH", t2, JobStatus.SUCCESS));
        em.persist(run("PRICE_REFRESH", t3, JobStatus.SUCCESS));
        em.flush();

        List<ScheduledJobRun> results = repository.findTop50ByJobNameOrderByStartedAtDesc("PRICE_REFRESH");

        assertThat(results).hasSize(2);
        assertThat(results).allMatch(r -> "PRICE_REFRESH".equals(r.getJobName()));
        assertThat(results.get(0).getStartedAt()).isEqualTo(t3);
        assertThat(results.get(1).getStartedAt()).isEqualTo(t1);
    }

    @Test
    void prePersist_defaultsStartedAtWhenNull() {
        ScheduledJobRun run = ScheduledJobRun.builder()
                .jobName("PRICE_REFRESH")
                .status(JobStatus.RUNNING)
                .build();

        em.persist(run);
        em.flush();

        assertThat(run.getStartedAt()).isNotNull();
    }

    private ScheduledJobRun run(String jobName, Instant startedAt, JobStatus status) {
        return ScheduledJobRun.builder()
                .jobName(jobName)
                .startedAt(startedAt)
                .finishedAt(startedAt.plusSeconds(30))
                .status(status)
                .itemsProcessed(1)
                .itemsSucceeded(status == JobStatus.SUCCESS ? 1 : 0)
                .itemsFailed(status == JobStatus.FAILED ? 1 : 0)
                .build();
    }
}
