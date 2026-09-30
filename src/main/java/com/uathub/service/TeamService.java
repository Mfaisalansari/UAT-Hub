package com.uathub.service;

import com.uathub.domain.AppUser;
import com.uathub.domain.Project;
import com.uathub.domain.Role;
import com.uathub.repo.AppUserRepository;
import com.uathub.repo.ProjectRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class TeamService {

    private final AppUserRepository users;
    private final ProjectRepository projects;

    public TeamService(AppUserRepository users, ProjectRepository projects) {
        this.users = users;
        this.projects = projects;
    }

    public List<AppUser> everyone() {
        return users.findAllByOrderByActiveDescNameAsc();
    }

    @Transactional
    public AppUser add(String name, String email, Role role, List<Long> projectIds) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Add a name");
        if (role == null) throw new IllegalArgumentException("Choose a role");
        AppUser u = new AppUser();
        u.setName(name.trim());
        u.setEmail(email == null || email.isBlank() ? null : email.trim());
        u.setRole(role);
        u.setAccessToken(Tokens.newToken());
        setProjects(u, projectIds);
        return users.save(u);
    }

    @Transactional
    public AppUser update(Long id, Role role, List<Long> projectIds) {
        AppUser u = get(id);
        if (role != null) u.setRole(role);
        setProjects(u, projectIds);
        return users.save(u);
    }

    /** New link; the old link and any browser using it stop working immediately. */
    @Transactional
    public AppUser regenerate(Long id) {
        AppUser u = get(id);
        u.setAccessToken(Tokens.newToken());
        u.setLinkOpenedAt(null);
        u.setActive(true);
        return users.save(u);
    }

    @Transactional
    public AppUser setActive(Long id, boolean active, Long actingUserId) {
        AppUser u = get(id);
        if (!active && u.getId().equals(actingUserId)) {
            throw new IllegalArgumentException("You can't revoke your own access");
        }
        u.setActive(active);
        if (active) {
            u.setAccessToken(Tokens.newToken());
            u.setLinkOpenedAt(null);
        }
        return users.save(u);
    }

    @Transactional
    public AppUser resetPin(Long id) {
        AppUser u = get(id);
        u.setPinHash(null);
        return users.save(u);
    }

    public AppUser get(Long id) {
        return users.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private void setProjects(AppUser u, List<Long> ids) {
        u.getProjects().clear();
        if (ids == null) return;
        for (Project p : projects.findAllById(ids)) u.getProjects().add(p);
    }
}
