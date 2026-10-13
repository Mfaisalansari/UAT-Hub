package com.uathub.repo;

import com.uathub.domain.AppUser;
import com.uathub.domain.Execution;
import com.uathub.domain.Scenario;
import com.uathub.domain.ScenarioDataSet;
import com.uathub.domain.TestRun;
import com.uathub.domain.UatCycle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionRepository extends JpaRepository<Execution, Long> {
    List<Execution> findByRun(TestRun run);
    List<Execution> findByScenario(Scenario scenario);
    boolean existsByDataSet(ScenarioDataSet dataSet);
    List<Execution> findByRunUatCycle(UatCycle cycle);
    List<Execution> findByRunUatCycleAndRunActiveTrueAndAssignee(UatCycle cycle, AppUser assignee);
    List<Execution> findByAssigneeAndRunActiveTrueAndRunUatCycleOpenTrue(AppUser assignee);
}
