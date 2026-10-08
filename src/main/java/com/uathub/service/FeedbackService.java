package com.uathub.service;

import com.uathub.domain.*;
import com.uathub.repo.AuditRepository;
import com.uathub.repo.FeedbackRepository;
import com.uathub.notify.Notifier;
import com.uathub.web.FeedbackForm;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

@Service
public class FeedbackService {

    private static final Set<String> STOP = Set.of("the", "and", "for", "not", "with", "when", "from",
            "this", "that", "should", "does", "are", "is", "on", "in", "to", "of", "after", "before");

    private final FeedbackRepository repo;
    private final AuditRepository audit;
    private final AttachmentStorage storage;
    private final Notifier notifier;

    public FeedbackService(FeedbackRepository repo, AuditRepository audit, AttachmentStorage storage, Notifier notifier) {
        this.repo = repo;
        this.audit = audit;
        this.storage = storage;
        this.notifier = notifier;
    }

    public Feedback load(Long id, AppUser me) {
        Feedback f = repo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!me.canAccess(f.getProject())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        return f;
    }

    public boolean canEdit(Feedback f, AppUser me) {
        boolean triager = me.getRole() == Role.QA_LEAD || me.getRole() == Role.ADMIN;
        if (triager) return f.getStage().isEditable();
        boolean owner = f.getRaisedBy() != null && f.getRaisedBy().getId().equals(me.getId());
        return owner && f.getStage().isOpenForTriage();
    }

    @Transactional
    public Feedback create(Project project, UatCycle cycle, AppUser by, FeedbackForm form, List<MultipartFile> files) {
        Feedback f = new Feedback();
        f.setProject(project);
        setCycle(f, cycle);
        f.setRaisedBy(by);
        apply(f, form);
        if (form.type() != null) f.setType(form.type());
        if (form.severity() != null) f.setSeverity(form.severity());
        f = repo.save(f);
        storage.saveAll(f, files);
        log(f, by, "Logged", files == null ? null : countFiles(files) + " attachment(s)");
        return f;
    }

    @Transactional
    public Feedback importRow(Project project, UatCycle cycle, AppUser by, Feedback f) {
        f.setProject(project);
        setCycle(f, cycle);
        f.setRaisedBy(by);
        f = repo.save(f);
        log(f, by, "Imported from Excel", null);
        return f;
    }

    @Transactional
    public void update(Feedback f, AppUser by, FeedbackForm form, List<MultipartFile> files, boolean resubmit) {
        if (!canEdit(f, by)) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        apply(f, form);
        if (form.type() != null) f.setType(form.type());
        if (form.severity() != null) f.setSeverity(form.severity());
        storage.saveAll(f, files);
        if (resubmit && f.getStage() == Stage.NEEDS_INFO) {
            f.setStage(Stage.LOGGED);
            log(f, by, "Updated and resubmitted", null);
        } else {
            log(f, by, "Edited", null);
        }
        repo.save(f);
    }

    /** QA lead actions: send to business, ask the tester for info, or close as duplicate. */
    @Transactional
    public void triage(Feedback f, AppUser by, String action, FeedbackType type, Severity severity,
                       String qaNote, String duplicateCode) {
        if (!f.getStage().isOpenForTriage()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Already past triage");
        }
        if (type != null) f.setType(type);
        if (severity != null) f.setSeverity(severity);
        f.setQaNote(blankToNull(qaNote));
        switch (action) {
            case "send" -> {
                f.setStage(Stage.BUSINESS_REVIEW);
                log(f, by, "Triaged and sent to business review", f.getType().getLabel() + ", " + f.getSeverity().getLabel());
                notifyBusiness(List.of(f), by);
            }
            case "info" -> {
                f.setStage(Stage.NEEDS_INFO);
                log(f, by, "Asked tester for more information", f.getQaNote());
                notifyInfoNeeded(f, by);
            }
            case "duplicate" -> {
                Long dupId = parseCode(duplicateCode);
                if (dupId == null || dupId.equals(f.getId()) || repo.findById(dupId).isEmpty()) {
                    throw new IllegalArgumentException("Enter the ID of the original item, for example UAT-0042");
                }
                f.setDuplicateOf(dupId);
                f.setStage(Stage.CLOSED);
                log(f, by, "Closed as duplicate", String.format("of UAT-%04d", dupId));
            }
            default -> throw new IllegalArgumentException("Unknown action");
        }
        repo.save(f);
    }

    @Transactional
    public void decide(Feedback f, AppUser by, Decision decision, String rationale, String targetRelease) {
        if (f.getStage() != Stage.BUSINESS_REVIEW) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "This item is not waiting for a decision");
        }
        if (decision == null) throw new IllegalArgumentException("Choose a decision");
        if (rationale == null || rationale.isBlank()) throw new IllegalArgumentException("Add a rationale for the decision");
        f.setDecision(decision);
        f.setDecisionRationale(rationale.trim());
        f.setTargetRelease(decision.isGoesToJira() ? blankToNull(targetRelease) : null);
        f.setDecidedBy(by);
        f.setDecidedAt(Instant.now());
        f.setStage(decision.isGoesToJira() ? Stage.DECIDED : Stage.CLOSED);
        log(f, by, "Decision: " + decision.getLabel(), rationale.trim());
        repo.save(f);
        List<AppUser> to = new java.util.ArrayList<>(notifier.people(f.getProject(), Role.QA_LEAD));
        if (f.getRaisedBy() != null) to.add(f.getRaisedBy());
        notifier.send(f.getProject(), to, by, f.getCode() + " decided: " + decision.getLabel(),
                f.getTitle() + "\n\nDecision: " + decision.getLabel()
                        + (f.getTargetRelease() == null ? "" : ", target " + f.getTargetRelease())
                        + "\nRationale: " + f.getDecisionRationale()
                        + (decision.isGoesToJira() ? "\n\nIt's ready for Jira." : ""),
                "/feedback/" + f.getId(), decision.isGoesToJira());
    }

    public record BulkResult(int done, int skipped) {}

    /**
     * Applies one triage action to many items. Items that aren't at a stage where the action makes sense
     * are skipped and counted, not failed. send/info only apply before business review; type/severity
     * while an item is still editable.
     */
    @Transactional
    public BulkResult bulk(Project project, AppUser by, List<Long> ids, String action,
                           FeedbackType type, Severity severity, String note) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("Tick at least one item");
        if ("info".equals(action) && (note == null || note.isBlank())) {
            throw new IllegalArgumentException("Say what information you need; it goes to each tester");
        }
        if ("type".equals(action) && type == null) throw new IllegalArgumentException("Choose the type to set");
        if ("severity".equals(action) && severity == null) throw new IllegalArgumentException("Choose the severity to set");
        int done = 0, skipped = 0;
        List<Feedback> sent = new ArrayList<>();
        for (Feedback f : repo.findAllById(ids)) {
            if (!f.getProject().getId().equals(project.getId()) || !by.canAccess(f.getProject())) { skipped++; continue; }
            switch (action) {
                case "send" -> {
                    if (!f.getStage().isOpenForTriage()) { skipped++; continue; }
                    f.setStage(Stage.BUSINESS_REVIEW);
                    log(f, by, "Triaged and sent to business review (bulk)", f.getType().getLabel() + ", " + f.getSeverity().getLabel());
                    sent.add(f);
                }
                case "info" -> {
                    if (!f.getStage().isOpenForTriage()) { skipped++; continue; }
                    f.setStage(Stage.NEEDS_INFO);
                    f.setQaNote(note.trim());
                    log(f, by, "Asked tester for more information (bulk)", f.getQaNote());
                    notifyInfoNeeded(f, by);
                }
                case "type" -> {
                    if (!f.getStage().isEditable()) { skipped++; continue; }
                    if (f.getType() == type) { skipped++; continue; }
                    log(f, by, "Type changed (bulk)", f.getType().getLabel() + " → " + type.getLabel());
                    f.setType(type);
                }
                case "severity" -> {
                    if (!f.getStage().isEditable()) { skipped++; continue; }
                    if (f.getSeverity() == severity) { skipped++; continue; }
                    log(f, by, "Severity changed (bulk)", f.getSeverity().getLabel() + " → " + severity.getLabel());
                    f.setSeverity(severity);
                }
                default -> throw new IllegalArgumentException("Choose an action");
            }
            repo.save(f);
            done++;
        }
        notifyBusiness(sent, by);
        return new BulkResult(done, skipped);
    }

    /** One message for business reviewers, however many items were sent at once. */
    void notifyBusiness(List<Feedback> items, AppUser by) {
        if (items.isEmpty()) return;
        Feedback first = items.get(0);
        StringBuilder body = new StringBuilder(items.size() == 1 ? "Waiting for your decision:\n" : items.size() + " items are waiting for your decision:\n");
        for (Feedback f : items) body.append("\n").append(f.getCode()).append("  ").append(f.getTitle())
                .append(" (").append(f.getSeverity().getLabel()).append(", ").append(f.getLobDivision()).append(")");
        String subject = items.size() == 1 ? first.getCode() + " needs a business decision" : items.size() + " items need a business decision";
        notifier.send(first.getProject(), notifier.people(first.getProject(), Role.BUSINESS), by, subject, body.toString(),
                items.size() == 1 ? "/review?id=" + first.getId() : "/review", true);
    }

    void notifyInfoNeeded(Feedback f, AppUser by) {
        if (f.getRaisedBy() == null) return;
        notifier.send(f.getProject(), List.of(f.getRaisedBy()), by, f.getCode() + ": more information needed",
                f.getTitle() + "\n\nQA asked: " + (f.getQaNote() == null ? "please add more detail." : f.getQaNote()),
                "/feedback/" + f.getId(), false);
    }

    @Transactional
    public void markInJira(Feedback f, AppUser by, String key, String url) {
        f.setJiraKey(key);
        f.setJiraUrl(url);
        f.setStage(Stage.IN_JIRA);
        log(f, by, "Jira issue created", key);
        repo.save(f);
    }

    public void log(Feedback f, AppUser by, String action, String detail) {
        audit.save(new AuditEntry(f, by, action, detail));
    }

    public List<AuditEntry> trail(Feedback f) {
        return audit.findByFeedbackOrderByAtAsc(f);
    }

    /** Simple word-overlap check used while typing a title, to catch duplicates early. */
    public List<Feedback> similar(Project project, String title, Long excludeId) {
        return similar(project, title, excludeId, 3, 0.25);
    }

    /** Wider net for the AI duplicate check: more results, lower bar. */
    public List<Feedback> similar(Project project, String text, Long excludeId, int limit) {
        return similar(project, text, excludeId, limit, 0.05);
    }

    private List<Feedback> similar(Project project, String title, Long excludeId, int limit, double minScore) {
        Set<String> words = words(title);
        if (words.size() < 2) return List.of();
        record Scored(Feedback f, double score) {}
        return repo.findByProjectOrderByIdDesc(project).stream()
                .filter(f -> excludeId == null || !f.getId().equals(excludeId))
                .map(f -> new Scored(f, jaccard(words, words(f.getTitle()))))
                .filter(s -> s.score() >= minScore)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(limit)
                .map(Scored::f)
                .toList();
    }

    @Transactional
    public void saveStory(Feedback f, AppUser by, String text, String action) {
        String t = text == null || text.isBlank() ? null : text.trim();
        if (t != null && t.length() > 4000) throw new IllegalArgumentException("Keep the user story under 4,000 characters");
        f.setStoryDraft(t);
        repo.save(f);
        log(f, by, t == null ? "User story removed" : action, null);
    }

    public static Long parseCode(String code) {
        if (code == null) return null;
        String digits = code.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return null;
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void setCycle(Feedback f, UatCycle cycle) {
        f.setUatCycle(cycle);
        f.setCycle(cycle == null ? null : cycle.getName());
    }

    private void apply(Feedback f, FeedbackForm form) {
        if (form.title() == null || form.title().isBlank()) throw new IllegalArgumentException("Add a title");
        f.setTitle(trim(form.title(), 300));
        f.setDescription(trim(form.description(), 4000));
        f.setExpected(trim(form.expected(), 2000));
        f.setActual(trim(form.actual(), 2000));
        f.setModule(trim(form.module(), 200));
        f.setScreen(trim(form.screen(), 200));
        f.setLob(blankToNull(form.lob()));
        f.setDivision(blankToNull(form.division()));
    }

    private static long countFiles(List<MultipartFile> files) {
        return files.stream().filter(x -> x != null && !x.isEmpty()).count();
    }

    private static Set<String> words(String s) {
        Set<String> out = new HashSet<>();
        if (s == null) return out;
        for (String w : s.toLowerCase().split("[^a-z0-9]+")) {
            if (w.length() > 2 && !STOP.contains(w)) out.add(w);
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String trim(String s, int max) {
        String v = blankToNull(s);
        return v == null || v.length() <= max ? v : v.substring(0, max);
    }
}
