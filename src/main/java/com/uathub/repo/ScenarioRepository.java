package com.uathub.repo;

import com.uathub.domain.Project;
import com.uathub.domain.Scenario;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ScenarioRepository extends JpaRepository<Scenario, Long> {
    List<Scenario> findByProjectOrderByCodeAsc(Project project);
    List<Scenario> findByProjectAndActiveTrueOrderByCodeAsc(Project project);
    Optional<Scenario> findByProjectAndCodeIgnoreCase(Project project, String code);
}
