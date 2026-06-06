package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.ScheduledJobRun;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScheduledJobRunRepository extends JpaRepository<ScheduledJobRun, Long> {

    List<ScheduledJobRun> findTop50ByOrderByStartedAtDesc();

    List<ScheduledJobRun> findTop50ByJobNameOrderByStartedAtDesc(String jobName);
}
