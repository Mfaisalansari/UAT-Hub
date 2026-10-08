package com.uathub.notify;

import com.uathub.config.UatHubProperties;
import com.uathub.domain.*;
import com.uathub.repo.AppUserRepository;
import com.uathub.repo.ExecutionRepository;
import com.uathub.repo.FeedbackRepository;
import com.uathub.repo.UatCycleRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/** Weekday morning email listing what is waiting for each person in open UAT cycles. Nothing waiting, no email. */
@Component
public class DigestJob {

    private final AppUserRepository users;
    private final UatCycleRepository cycles;
    private final FeedbackRepository feedback;
    private final ExecutionRepository executions;
    private final NotificationDispatcher dispatcher;
    private final UatHubProperties props;

    public DigestJob(AppUserRepository users, UatCycleRepository cycles, FeedbackRepository feedback,
                     ExecutionRepository executions, NotificationDispatcher dispatcher, UatHubProperties props) {
        this.users = users;
        this.cycles = cycles;
        this.feedback = feedback;
        this.executions = executions;
        this.dispatcher = dispatcher;
        this.props = props;
    }

    @Scheduled(cron = "${uathub.notifications.digest-cron:0 0 9 * * MON-FRI}")
    @Transactional(readOnly = true)
    public void run() {
        Boolean on = props.notifySafe().digestEnabled();
        if (Boolean.FALSE.equals(on) || !dispatcher.emailConfigured()) return;
        List<UatCycle> open = cycles.findByOpenTrue();
        for (AppUser u : users.findAllByOrderByActiveDescNameAsc()) {
            if (!u.isEmailable()) continue;
            List<String> lines = new ArrayList<>();
            for (UatCycle c : open) {
                if (!u.canAccess(c.getProject())) continue;
                String where = c.getProject().getName() + " / " + c.getName() + ": ";
                if (u.getRole() == Role.BUSINESS || u.getRole() == Role.ADMIN) {
                    long n = feedback.countByUatCycleAndStage(c, Stage.BUSINESS_REVIEW);
                    if (n > 0) lines.add(where + n + " item(s) waiting for your decision  " + props.url("/review"));
                }
                if (u.getRole() == Role.QA_LEAD || u.getRole() == Role.ADMIN) {
                    long t = feedback.countByUatCycleAndStage(c, Stage.LOGGED);
                    long j = feedback.countByUatCycleAndStage(c, Stage.DECIDED);
                    if (t > 0) lines.add(where + t + " item(s) waiting for triage  " + props.url("/?stage=LOGGED"));
                    if (j > 0) lines.add(where + j + " decided item(s) ready for Jira  " + props.url("/jira"));
                }
            }
            long todo = executions.findByAssigneeAndRunActiveTrueAndRunUatCycleOpenTrue(u).stream()
                    .filter(e -> !e.getStatus().isDone()).count();
            long retest = executions.findByAssigneeAndRunActiveTrueAndRunUatCycleOpenTrue(u).stream()
                    .filter(e -> e.getStatus() == ExecStatus.RETEST).count();
            if (todo > 0) lines.add(todo + " scenario(s) to run" + (retest > 0 ? ", " + retest + " of them re-tests" : "")
                    + "  " + props.url("/my"));
            long info = feedback.findByRaisedByAndStageAndUatCycleOpenTrue(u, Stage.NEEDS_INFO).size();
            if (info > 0) lines.add(info + " of your item(s) need more information from you  " + props.url("/?stage=NEEDS_INFO"));
            if (lines.isEmpty()) continue;
            dispatcher.email(u.getEmail(), "Your UAT to-do",
                    "Good morning " + u.getName() + ",\n\nWaiting for you in UAT Hub:\n\n- " + String.join("\n- ", lines)
                            + "\n\n-- UAT Hub (turn these emails off from your admin's Team & access page)");
        }
    }
}
