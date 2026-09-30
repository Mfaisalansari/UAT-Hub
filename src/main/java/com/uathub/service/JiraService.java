package com.uathub.service;

import com.uathub.config.UatHubProperties;
import com.uathub.domain.*;
import com.uathub.repo.FeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Creates Jira issues from decided feedback.
 * Cloud: REST v3 (description in Atlassian Document Format), basic auth with email + API token.
 * Data Center: REST v2 (plain text description), bearer personal access token.
 */
@Service
public class JiraService {

    private static final Logger log = LoggerFactory.getLogger(JiraService.class);

    public record Result(Long feedbackId, String code, boolean ok, String key, String message) implements java.io.Serializable {}

    private final UatHubProperties.Jira cfg;
    private final FeedbackRepository feedbackRepo;
    private final FeedbackService feedbackService;
    private final AttachmentStorage storage;

    public JiraService(UatHubProperties props, FeedbackRepository feedbackRepo,
                       FeedbackService feedbackService, AttachmentStorage storage) {
        this.cfg = props.jira();
        this.feedbackRepo = feedbackRepo;
        this.feedbackService = feedbackService;
        this.storage = storage;
    }

    public boolean configured() {
        return cfg != null && cfg.configured();
    }

    public List<Result> push(List<Long> ids, AppUser by) {
        List<Result> results = new ArrayList<>();
        if (!configured()) {
            results.add(new Result(null, "-", false, null,
                    "Jira is not configured. Set JIRA_BASE_URL and JIRA_TOKEN (plus JIRA_EMAIL for Cloud) and restart."));
            return results;
        }
        RestClient client = client();
        for (Long id : ids) {
            Feedback f = feedbackRepo.findById(id).orElse(null);
            if (f == null || !by.canAccess(f.getProject())) {
                results.add(new Result(id, String.valueOf(id), false, null, "Not found"));
                continue;
            }
            if (f.getStage() != Stage.DECIDED || f.getJiraKey() != null) {
                results.add(new Result(id, f.getCode(), false, f.getJiraKey(), "Not ready for Jira or already created"));
                continue;
            }
            if (blank(f.getProject().getJiraProjectKey())) {
                results.add(new Result(id, f.getCode(), false, null, "Set the Jira project key for this project first"));
                continue;
            }
            try {
                String key = create(client, f);
                String url = cfg.cleanBaseUrl() + "/browse/" + key;
                feedbackService.markInJira(f, by, key, url);
                String note = uploadAttachments(client, f, key);
                results.add(new Result(id, f.getCode(), true, key, note));
            } catch (RestClientResponseException e) {
                String body = e.getResponseBodyAsString(StandardCharsets.UTF_8);
                log.warn("Jira create failed for {}: {} {}", f.getCode(), e.getStatusCode(), body);
                results.add(new Result(id, f.getCode(), false, null,
                        "Jira said " + e.getStatusCode().value() + ": " + shorten(body, 300)));
            } catch (Exception e) {
                log.warn("Jira create failed for {}", f.getCode(), e);
                results.add(new Result(id, f.getCode(), false, null, shorten(e.getMessage(), 300)));
            }
        }
        return results;
    }

    /** What will be sent, shown on the Jira push screen before creating anything. */
    public Map<String, Object> preview(Feedback f) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("summary", summary(f));
        m.put("issueType", f.getProject().issueTypeFor(f.getDecision()));
        m.put("labels", labels(f));
        m.put("components", split(f.getProject().getJiraComponents()));
        m.put("fixVersion", fixVersion(f));
        return m;
    }

    private String create(RestClient client, Feedback f) {
        Project p = f.getProject();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", Map.of("key", p.getJiraProjectKey().trim()));
        fields.put("summary", summary(f));
        fields.put("issuetype", Map.of("name", p.issueTypeFor(f.getDecision())));
        fields.put("labels", labels(f));
        fields.put("description", cfg.cloud() ? adf(sections(f)) : plain(sections(f)));
        List<String> comps = split(p.getJiraComponents());
        if (!comps.isEmpty()) fields.put("components", comps.stream().map(c -> Map.of("name", c)).toList());
        String fix = fixVersion(f);
        if (fix != null) fields.put("fixVersions", List.of(Map.of("name", fix)));

        @SuppressWarnings("unchecked")
        Map<String, Object> response = client.post()
                .uri(api() + "/issue")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("fields", fields))
                .retrieve()
                .body(Map.class);
        if (response == null || response.get("key") == null) throw new IllegalStateException("Jira returned no issue key");
        return String.valueOf(response.get("key"));
    }

    private String uploadAttachments(RestClient client, Feedback f, String key) {
        List<Attachment> files = storage.list(f);
        int failed = 0;
        for (Attachment a : files) {
            try {
                MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
                body.add("file", new FileSystemResource(storage.pathOf(a)) {
                    @Override
                    public String getFilename() { return a.getFileName(); }
                });
                client.post()
                        .uri(api() + "/issue/" + key + "/attachments")
                        .header("X-Atlassian-Token", "no-check")
                        .contentType(MediaType.MULTIPART_FORM_DATA)
                        .body(body)
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                failed++;
                log.warn("Attachment upload failed for {} on {}", a.getFileName(), key, e);
            }
        }
        if (files.isEmpty()) return "Created";
        return failed == 0 ? "Created with " + files.size() + " attachment(s)"
                : "Created, but " + failed + " of " + files.size() + " attachment(s) failed to upload";
    }

    private RestClient client() {
        String auth;
        if (cfg.cloud()) {
            String raw = (cfg.email() == null ? "" : cfg.email()) + ":" + cfg.token();
            auth = "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
        } else {
            auth = "Bearer " + cfg.token();
        }
        return RestClient.builder()
                .baseUrl(cfg.cleanBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, auth)
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private String api() {
        return cfg.cloud() ? "/rest/api/3" : "/rest/api/2";
    }

    private static String summary(Feedback f) {
        String s = "[UAT] " + f.getTitle();
        return s.length() > 250 ? s.substring(0, 250) : s;
    }

    private static List<String> labels(Feedback f) {
        List<String> out = new ArrayList<>();
        out.add("UAT-Feedback");
        if (!blank(f.getCycle())) out.add(label("UAT-" + f.getCycle()));
        if (!blank(f.getLob())) out.add(label(f.getLob()));
        if (!blank(f.getDivision())) out.add(label(f.getDivision()));
        out.add(label(f.getCode()));
        for (String extra : split(f.getProject().getJiraLabels())) out.add(label(extra));
        return out.stream().distinct().toList();
    }

    private static String label(String s) {
        return s.trim().replaceAll("\\s+", "-");
    }

    private static String fixVersion(Feedback f) {
        if (f.getDecision() == Decision.FUTURE_RELEASE) return null;
        String v = f.getProject().getJiraFixVersion();
        return blank(v) ? null : v.trim();
    }

    private static List<String[]> sections(Feedback f) {
        List<String[]> s = new ArrayList<>();
        s.add(new String[]{"UAT reference", f.getCode() + " (" + f.getLobDivision() + ")"});
        s.add(new String[]{"Where", f.getWhere()});
        s.add(new String[]{"Type / severity", f.getType().getLabel() + " / " + f.getSeverity().getLabel()});
        s.add(new String[]{"Details", f.getDescription()});
        s.add(new String[]{"Expected", f.getExpected()});
        s.add(new String[]{"Actual", f.getActual()});
        s.add(new String[]{"QA note", f.getQaNote()});
        s.add(new String[]{"Business decision", f.getDecision().getLabel()
                + (blank(f.getTargetRelease()) ? "" : ", target " + f.getTargetRelease())});
        s.add(new String[]{"Rationale", f.getDecisionRationale()});
        s.add(new String[]{"Decided by", f.getDecidedBy() == null ? null : f.getDecidedBy().getName()});
        s.add(new String[]{"Raised by", f.getRaisedBy() == null ? null : f.getRaisedBy().getName()});
        return s.stream().filter(x -> !blank(x[1])).toList();
    }

    private static String plain(List<String[]> sections) {
        StringBuilder sb = new StringBuilder();
        for (String[] s : sections) sb.append("*").append(s[0]).append(":* ").append(s[1]).append("\n\n");
        return sb.toString().trim();
    }

    private static Map<String, Object> adf(List<String[]> sections) {
        List<Object> content = new ArrayList<>();
        for (String[] s : sections) {
            content.add(Map.of("type", "paragraph", "content", List.of(
                    Map.of("type", "text", "text", s[0] + ": ", "marks", List.of(Map.of("type", "strong"))),
                    Map.of("type", "text", "text", s[1]))));
        }
        return Map.of("type", "doc", "version", 1, "content", content);
    }

    private static List<String> split(String csv) {
        if (blank(csv)) return List.of();
        return Arrays.stream(csv.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String shorten(String s, int max) {
        if (s == null) return "Unknown error";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
