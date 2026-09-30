package com.uathub.repo;

import com.uathub.domain.Feedback;
import com.uathub.domain.Project;
import com.uathub.domain.Stage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FeedbackRepository extends JpaRepository<Feedback, Long> {
    List<Feedback> findByProjectOrderByIdDesc(Project project);
    List<Feedback> findByProjectAndStageOrderByIdAsc(Project project, Stage stage);
    List<Feedback> findTop10ByProjectAndStageOrderByUpdatedAtDesc(Project project, Stage stage);
    long countByProject(Project project);
    long countByProjectAndStage(Project project, Stage stage);
}
