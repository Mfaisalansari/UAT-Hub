package com.uathub.repo;

import com.uathub.domain.Project;
import com.uathub.domain.UatCycle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface UatCycleRepository extends JpaRepository<UatCycle, Long> {
    List<UatCycle> findByProjectOrderByIdDesc(Project project);
    Optional<UatCycle> findByProjectAndNameIgnoreCase(Project project, String name);
    List<UatCycle> findByOpenTrue();
}
