package com.uathub.repo;

import com.uathub.domain.Project;
import com.uathub.domain.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TestRunRepository extends JpaRepository<TestRun, Long> {
    List<TestRun> findByProjectOrderByIdDesc(Project project);
    Optional<TestRun> findFirstByProjectAndActiveTrueOrderByIdDesc(Project project);
}
