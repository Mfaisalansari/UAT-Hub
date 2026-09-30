package com.uathub.repo;

import com.uathub.domain.AuditEntry;
import com.uathub.domain.Feedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditRepository extends JpaRepository<AuditEntry, Long> {
    List<AuditEntry> findByFeedbackOrderByAtAsc(Feedback feedback);
}
