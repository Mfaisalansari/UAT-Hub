package com.uathub.repo;

import com.uathub.domain.AppUser;
import com.uathub.domain.Execution;
import com.uathub.domain.TestRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ExecutionRepository extends JpaRepository<Execution, Long> {
    List<Execution> findByRun(TestRun run);
    List<Execution> findByRunAndAssignee(TestRun run, AppUser assignee);
    long countByRunAndAssignee(TestRun run, AppUser assignee);
}
