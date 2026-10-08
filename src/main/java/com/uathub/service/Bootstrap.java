package com.uathub.service;

import com.uathub.config.UatHubProperties;
import com.uathub.domain.*;
import com.uathub.repo.AppUserRepository;
import com.uathub.repo.FeedbackRepository;
import com.uathub.repo.ProjectRepository;
import com.uathub.repo.TestRunRepository;
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
 * First start: creates a starter project with a first UAT cycle, and an Admin, then prints the admin link.
 * Every start: moves data from before UAT cycles existed into a cycle,
 * and prints the active admin links so the admin can never be locked out.
 */
@Component
public class Bootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(Bootstrap.class);

    private final AppUserRepository users;
    private final ProjectRepository projects;
    private final FeedbackRepository feedback;
    private final TestRunRepository runs;
    private final CycleService cycles;
    private final UatHubProperties props;

    public Bootstrap(AppUserRepository users, ProjectRepository projects, FeedbackRepository feedback,
                     TestRunRepository runs, CycleService cycles, UatHubProperties props) {
        this.users = users;
        this.projects = projects;
        this.feedback = feedback;
        this.runs = runs;
        this.cycles = cycles;
        this.props = props;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        Files.createDirectories(Path.of(props.dataDir(), "attachments"));

        if (users.count() == 0) {
            Project p = new Project();
            p.setName("My project");
            projects.save(p);
            cycles.findOrCreate(p, "UAT cycle 1");

            AppUser admin = new AppUser();
            admin.setName("Admin");
            admin.setRole(Role.ADMIN);
            admin.setAccessToken(Tokens.newToken());
            users.save(admin);
            log.info("First start: created starter project, first UAT cycle and Admin user.");
        }

        for (Project p : projects.findAll()) moveIntoCycle(p);

        List<AppUser> admins = users.findByRoleAndActiveTrue(Role.ADMIN);
        StringBuilder sb = new StringBuilder("\n\n================ UAT Hub admin access ================\n");
        for (AppUser a : admins) {
            sb.append("  ").append(a.getName()).append(": ").append(props.linkFor(a.getAccessToken())).append("\n");
        }
        sb.append("  Open the link in your browser. Keep it private: it works like a password.\n");
        sb.append("======================================================\n");
        log.info(sb.toString());
    }

    /** Runs and feedback created before UAT cycles existed go into the project's old "current cycle". */
    private void moveIntoCycle(Project p) {
        List<Feedback> loose = feedback.findByProjectAndUatCycleIsNull(p);
        List<TestRun> looseRuns = runs.findByProjectAndUatCycleIsNull(p);
        boolean noCycle = cycles.cycles(p).isEmpty();
        if (loose.isEmpty() && looseRuns.isEmpty() && !noCycle) return;
        UatCycle c = cycles.findOrCreate(p, p.getCurrentCycle());
        for (Feedback f : loose) {
            f.setUatCycle(c);
            if (f.getCycle() == null) f.setCycle(c.getName());
            feedback.save(f);
        }
        for (TestRun r : looseRuns) {
            r.setUatCycle(c);
            runs.save(r);
        }
        if (!loose.isEmpty() || !looseRuns.isEmpty()) {
            log.info("Moved {} feedback item(s) and {} run(s) of {} into UAT cycle '{}'.",
                    loose.size(), looseRuns.size(), p.getName(), c.getName());
        }
    }
}
