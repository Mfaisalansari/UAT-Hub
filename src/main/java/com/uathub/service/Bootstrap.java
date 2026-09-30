package com.uathub.service;

import com.uathub.config.UatHubProperties;
import com.uathub.domain.AppUser;
import com.uathub.domain.Project;
import com.uathub.domain.Role;
import com.uathub.repo.AppUserRepository;
import com.uathub.repo.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * First start: creates a starter project and an Admin, then prints the admin link to the console.
 * Every start: prints the active admin links again, so the admin can never be locked out.
 */
@Component
public class Bootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    private final AppUserRepository users;
    private final ProjectRepository projects;
    private final UatHubProperties props;

    public Bootstrap(AppUserRepository users, ProjectRepository projects, UatHubProperties props) {
        this.users = users;
        this.projects = projects;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        Files.createDirectories(Path.of(props.dataDir(), "attachments"));
        if (users.count() == 0) {
            Project p = new Project();
            p.setName("My project");
            p.setCurrentCycle("UAT cycle 1");
            projects.save(p);

            AppUser admin = new AppUser();
            admin.setName("Admin");
            admin.setRole(Role.ADMIN);
            admin.setAccessToken(Tokens.newToken());
            users.save(admin);
            log.info("First start: created starter project and Admin user.");
        }
        List<AppUser> admins = users.findByRoleAndActiveTrue(Role.ADMIN);
        StringBuilder sb = new StringBuilder("\n\n================ UAT Hub admin access ================\n");
        for (AppUser a : admins) {
            sb.append("  ").append(a.getName()).append(": ").append(props.linkFor(a.getAccessToken())).append("\n");
        }
        sb.append("  Open the link in your browser. Keep it private: it works like a password.\n");
        sb.append("======================================================\n");
        log.info(sb.toString());
    }
}
