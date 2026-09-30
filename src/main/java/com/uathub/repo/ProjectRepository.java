package com.uathub.repo;

import com.uathub.domain.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {
    List<Project> findAllByOrderByNameAsc();
    Optional<Project> findByNameIgnoreCase(String name);
}
