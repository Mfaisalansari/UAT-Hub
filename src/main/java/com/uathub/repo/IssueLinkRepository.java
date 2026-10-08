package com.uathub.repo;

import com.uathub.domain.Execution;
import com.uathub.domain.Feedback;
import com.uathub.domain.IssueLink;
import com.uathub.domain.TestRun;
import com.uathub.domain.UatCycle;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface IssueLinkRepository extends JpaRepository<IssueLink, Long> {
    List<IssueLink> findByExecution(Execution execution);
    List<IssueLink> findByFeedback(Feedback feedback);
    List<IssueLink> findByExecutionRun(TestRun run);
    List<IssueLink> findByExecutionRunUatCycle(UatCycle cycle);
    boolean existsByFeedbackAndExecution(Feedback feedback, Execution execution);
}
