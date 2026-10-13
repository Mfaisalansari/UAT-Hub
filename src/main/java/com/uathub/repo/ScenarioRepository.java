package com.uathub.repo;

import com.uathub.domain.Project;
import com.uathub.domain.Scenario;
import com.uathub.domain.UatCycle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {
    List<Scenario> findByUatCycleOrderByCodeAsc(UatCycle cycle);
    List<Scenario> findByUatCycleAndActiveTrueOrderByCodeAsc(UatCycle cycle);
    Optional<Scenario> findByUatCycleAndCodeIgnoreCase(UatCycle cycle, String code);
    List<Scenario> findByProjectAndUatCycleIsNull(Project project);
    long countByUatCycle(UatCycle cycle);
    List<Scenario> findByProject(Project project);
}
