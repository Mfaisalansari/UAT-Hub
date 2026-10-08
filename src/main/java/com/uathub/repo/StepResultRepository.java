package com.uathub.repo;

import com.uathub.domain.Execution;
import com.uathub.domain.ScenarioStep;
import com.uathub.domain.StepResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StepResultRepository extends JpaRepository<StepResult, Long> {
    List<StepResult> findByExecutionAndAttempt(Execution execution, int attempt);
    boolean existsByStep(ScenarioStep step);
}
