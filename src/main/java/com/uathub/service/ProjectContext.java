package com.uathub.service;

import com.uathub.domain.AppUser;
import com.uathub.domain.Project;
import com.uathub.domain.Role;
import com.uathub.repo.AppUserRepository;
import com.uathub.repo.ProjectRepository;
import com.uathub.security.CurrentUser;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;

/** Which project the person is working in (remembered per browser session). */
@Component
public class ProjectContext {

    private static final String KEY = "projectId";

    private final AppUserRepository users;
    private final ProjectRepository projects;

    public ProjectContext(AppUserRepository users, ProjectRepository projects) {
        this.users = users;
        this.projects = projects;
    }

    public AppUser user(CurrentUser me) {
        return users.findById(me.id())
                .filter(AppUser::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN));
    }

    public List<Project> accessible(CurrentUser me) {
        AppUser u = user(me);
        if (u.getRole() == Role.ADMIN) return projects.findAllByOrderByNameAsc();
        return u.getProjects().stream().sorted(Comparator.comparing(Project::getName)).toList();
    }

    /** Current project, or null when the person has none assigned. */
    public Project current(CurrentUser me, HttpSession session) {
        List<Project> mine = accessible(me);
        if (mine.isEmpty()) return null;
        Object id = session.getAttribute(KEY);
        return mine.stream().filter(p -> p.getId().equals(id)).findFirst().orElse(mine.get(0));
    }

    public Project require(CurrentUser me, HttpSession session) {
        Project p = current(me, session);
        if (p == null) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No project assigned");
        return p;
    }

    public void select(CurrentUser me, HttpSession session, Long projectId) {
        boolean allowed = accessible(me).stream().anyMatch(p -> p.getId().equals(projectId));
        if (!allowed) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        session.setAttribute(KEY, projectId);
    }
}
