package com.uathub.notify;

import com.uathub.domain.AppUser;
import com.uathub.domain.Project;
import com.uathub.domain.Role;
import com.uathub.repo.AppUserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.*;

/** Small facade services call to announce something. Never blocks or fails the caller. */
@Component
public class Notifier {

    private final ApplicationEventPublisher events;
    private final AppUserRepository users;

    public Notifier(ApplicationEventPublisher events, AppUserRepository users) {
        this.events = events;
        this.users = users;
    }

    /** Active people with the given roles who are assigned to the project (admins only when listed explicitly). */
    public List<AppUser> people(Project project, Role... roles) {
        Set<Role> wanted = EnumSet.copyOf(Arrays.asList(roles));
        List<AppUser> out = new ArrayList<>();
        for (AppUser u : users.findAllByOrderByActiveDescNameAsc()) {
            if (!u.isActive() || !wanted.contains(u.getRole())) continue;
            boolean assigned = u.getProjects().stream().anyMatch(p -> p.getId().equals(project.getId()));
            if (assigned || (u.getRole() == Role.ADMIN && wanted.contains(Role.ADMIN))) out.add(u);
        }
        return out;
    }

    /** Sends to the given people, leaving out whoever caused it. */
    public void send(Project project, Collection<AppUser> to, AppUser actor, String subject, String body, String path, boolean teams) {
        Set<Long> ids = new LinkedHashSet<>();
        for (AppUser u : to) {
            if (u == null || (actor != null && u.getId().equals(actor.getId()))) continue;
            ids.add(u.getId());
        }
        if (ids.isEmpty() && !teams) return;
        events.publishEvent(new Notice(project.getId(), ids, subject, body, path, teams));
    }
}
