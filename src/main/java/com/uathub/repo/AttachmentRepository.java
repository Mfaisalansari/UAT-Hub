package com.uathub.repo;

import com.uathub.domain.Attachment;
import com.uathub.domain.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {
    List<Attachment> findByFeedbackOrderByIdAsc(Feedback feedback);
}
