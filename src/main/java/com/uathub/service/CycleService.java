package com.uathub.service;

import com.uathub.domain.Project;
import com.uathub.domain.UatCycle;
import com.uathub.repo.UatCycleRepository;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * UAT cycles within a project, and which one the person is looking at (remembered per browser session,
 * per project). Default: the newest open cycle, otherwise the newest cycle.
 */
@Service
public class CycleService {

    private final UatCycleRepository repo;

    public CycleService(UatCycleRepository repo) {
        this.repo = repo;
    }

    public List<UatCycle> cycles(Project project) {
        return repo.findByProjectOrderByIdDesc(project);
    }

    public UatCycle current(Project project, HttpSession session) {
        if (project == null) return null;
        List<UatCycle> all = cycles(project);
        if (all.isEmpty()) return null;
        Object id = session.getAttribute(key(project));
        return all.stream().filter(c -> c.getId().equals(id)).findFirst()
                .orElseGet(() -> all.stream().filter(UatCycle::isOpen).findFirst().orElse(all.get(0)));
    }

    public UatCycle require(Project project, HttpSession session) {
        UatCycle c = current(project, session);
        if (c == null) throw new IllegalArgumentException("Create a UAT cycle for this project first (UAT cycles page).");
        return c;
    }

    /** The current cycle, which must be open to take new feedback, runs or results. */
    public UatCycle requireOpen(Project project, HttpSession session) {
        UatCycle c = require(project, session);
        if (!c.isOpen()) throw new IllegalArgumentException(c.getName() + " is closed. Switch to an open UAT cycle in the sidebar.");
        return c;
    }

    public void select(Project project, HttpSession session, Long id) {
        session.setAttribute(key(project), load(id, project).getId());
    }

    public UatCycle load(Long id, Project project) {
        UatCycle c = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!c.getProject().getId().equals(project.getId())) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        return c;
    }

    @Transactional
    public UatCycle save(Project project, Long id, String name, String releaseName, LocalDate start, LocalDate end) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name the UAT cycle, e.g. November 2026 release");
        if (start != null && end != null && end.isBefore(start)) throw new IllegalArgumentException("The end date is before the start date");
        String n = name.trim();
        Optional<UatCycle> same = repo.findByProjectAndNameIgnoreCase(project, n);
        if (same.isPresent() && !same.get().getId().equals(id)) {
            throw new IllegalArgumentException("This project already has a cycle called " + n);
        }
        UatCycle c = id == null ? new UatCycle() : load(id, project);
        c.setProject(project);
        c.setName(n);
        c.setReleaseName(releaseName == null || releaseName.isBlank() ? null : releaseName.trim());
        c.setStartDate(start);
        c.setEndDate(end);
        return repo.save(c);
    }

    @Transactional
    public UatCycle setOpen(UatCycle c, boolean open) {
        c.setOpen(open);
        return repo.save(c);
    }

    /** Used at start-up to move data from before cycles existed into a cycle. */
    @Transactional
    public UatCycle findOrCreate(Project project, String name) {
        String n = name == null || name.isBlank() ? "UAT cycle 1" : name.trim();
        return repo.findByProjectAndNameIgnoreCase(project, n).orElseGet(() -> {
            UatCycle c = new UatCycle();
            c.setProject(project);
            c.setName(n);
            return repo.save(c);
        });
    }

    private static String key(Project project) {
        return "cycleId:" + project.getId();
    }
}
