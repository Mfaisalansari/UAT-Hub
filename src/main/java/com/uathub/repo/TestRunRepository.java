package com.uathub.repo;

import com.uathub.domain.Project;
import com.uathub.domain.TestRun;
import com.uathub.domain.UatCycle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TestRunRepository extends JpaRepository<TestRun, Long> {
    List<TestRun> findByUatCycleOrderByIdDesc(UatCycle cycle);
    List<TestRun> findByProjectAndUatCycleIsNull(Project project);
    long countByUatCycle(UatCycle cycle);
}
