package com.uathub.repo;

import com.uathub.domain.Comment;
import com.uathub.domain.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {
    List<Comment> findByFeedbackOrderByCreatedAtAsc(Feedback feedback);
    long countByFeedback(Feedback feedback);
}
