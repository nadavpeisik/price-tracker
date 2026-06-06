package com.np.pricehunt.backend.repository;

import com.np.pricehunt.backend.domain.ScheduledJobRun;
import com.np.pricehunt.backend.domain.ScheduledJobRunItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ScheduledJobRunItemRepository extends JpaRepository<ScheduledJobRunItem, Long> {

    List<ScheduledJobRunItem> findByRun(ScheduledJobRun run);
}
