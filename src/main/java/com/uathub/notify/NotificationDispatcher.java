package com.uathub.notify;

import com.uathub.config.UatHubProperties;
import com.uathub.domain.AppUser;
import com.uathub.domain.Project;
import com.uathub.repo.AppUserRepository;
import com.uathub.repo.ProjectRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/** Delivers notices by email and to Microsoft Teams, in the background after the change is saved. */
@Component
public class NotificationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcher.class);

    private final ObjectProvider<JavaMailSender> mail;
    private final AppUserRepository users;
    private final ProjectRepository projects;
    private final UatHubProperties props;
    private final RestClient http = RestClient.create();

    public NotificationDispatcher(ObjectProvider<JavaMailSender> mail, AppUserRepository users,
                                  ProjectRepository projects, UatHubProperties props) {
        this.mail = mail;
        this.users = users;
        this.projects = projects;
        this.props = props;
    }

    public boolean emailConfigured() {
        return mail.getIfAvailable() != null;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void deliver(Notice n) {
        String link = props.url(n.path());
        if (!n.userIds().isEmpty()) {
            for (AppUser u : users.findAllById(n.userIds())) {
                if (u.isEmailable()) email(u.getEmail(), n.subject(), "Hi " + u.getName() + ",\n\n" + n.body()
                        + "\n\nOpen it: " + link + "\n\n-- UAT Hub (turn these emails off from your admin's Team & access page)");
            }
        }
        if (n.teams()) {
            projects.findById(n.projectId()).map(Project::getTeamsWebhook)
                    .filter(w -> w != null && !w.isBlank())
                    .ifPresent(w -> teams(w, n.subject(), n.body(), link));
        }
    }

    public boolean email(String to, String subject, String text) {
        JavaMailSender sender = mail.getIfAvailable();
        if (sender == null) return false;
        try {
            SimpleMailMessage m = new SimpleMailMessage();
            String from = props.notifySafe().mailFrom();
            if (from != null && !from.isBlank()) m.setFrom(from);
            m.setTo(to);
            m.setSubject("[UAT Hub] " + subject);
            m.setText(text);
            sender.send(m);
            return true;
        } catch (Exception e) {
            log.warn("Email to {} failed: {}", to, e.getMessage());
            return false;
        }
    }

    public String teamsTest(Project p) {
        return teams(p.getTeamsWebhook(), "UAT Hub is connected",
                "Notifications for " + p.getName() + " will appear in this channel.", props.url("/"));
    }

    /**
     * Posts an Adaptive Card, the format accepted by Teams "Workflows" webhooks (which replace the
     * retired Office 365 connector webhooks). Returns an error message, or null when it worked.
     */
    public String teams(String webhook, String title, String text, String link) {
        Map<String, Object> card = Map.of(
                "$schema", "http://adaptivecards.io/schemas/adaptive-card.json",
                "type", "AdaptiveCard",
                "version", "1.4",
                "body", List.of(
                        Map.of("type", "TextBlock", "text", title, "weight", "Bolder", "size", "Medium", "wrap", true),
                        Map.of("type", "TextBlock", "text", text, "wrap", true)),
                "actions", List.of(Map.of("type", "Action.OpenUrl", "title", "Open in UAT Hub", "url", link)));
        Map<String, Object> message = Map.of("type", "message", "attachments", List.of(
                Map.of("contentType", "application/vnd.microsoft.card.adaptive", "content", card)));
        try {
            http.post().uri(webhook).contentType(MediaType.APPLICATION_JSON).body(message).retrieve().toBodilessEntity();
            return null;
        } catch (Exception e) {
            log.warn("Teams message failed: {}", e.getMessage());
            return e.getMessage();
        }
    }
}
