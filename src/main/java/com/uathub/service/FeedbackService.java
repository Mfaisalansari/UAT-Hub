package com.uathub.service;

import com.uathub.domain.*;
import com.uathub.repo.AuditRepository;
import com.uathub.repo.FeedbackRepository;
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

    public FeedbackService(FeedbackRepository repo, AuditRepository audit, AttachmentStorage storage) {
        this.repo = repo;
        this.audit = audit;
        this.storage = storage;
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
    public Feedback create(Project project, AppUser by, FeedbackForm form, List<MultipartFile> files) {
        Feedback f = new Feedback();
        f.setProject(project);
        f.setCycle(project.getCurrentCycle());
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
    public Feedback importRow(Project project, AppUser by, Feedback f) {
        f.setProject(project);
        f.setCycle(project.getCurrentCycle());
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
            }
            case "info" -> {
                f.setStage(Stage.NEEDS_INFO);
                log(f, by, "Asked tester for more information", f.getQaNote());
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
        Set<String> words = words(title);
        if (words.size() < 2) return List.of();
        record Scored(Feedback f, double score) {}
        return repo.findByProjectOrderByIdDesc(project).stream()
                .filter(f -> excludeId == null || !f.getId().equals(excludeId))
                .map(f -> new Scored(f, jaccard(words, words(f.getTitle()))))
                .filter(s -> s.score() >= 0.25)
                .sorted(Comparator.comparingDouble(Scored::score).reversed())
                .limit(3)
                .map(Scored::f)
                .toList();
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
