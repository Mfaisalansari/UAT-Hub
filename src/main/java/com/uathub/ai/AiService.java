package com.uathub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.uathub.domain.*;
import com.uathub.repo.FeedbackRepository;
import com.uathub.service.FeedbackService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/** AI suggestions. They only ever suggest: a person accepts, edits or ignores every one. */
@Service
public class AiService {

    public record Suggestion(String type, String severity, String reason) {}

    public record DuplicateHit(Long id, String code, String title, String stage, String reason) implements java.io.Serializable {}

    private static final String CONTEXT = "You help run user acceptance testing (UAT) of a Pega-based insurance "
            + "underwriting application with lines of business such as Casualty, Property, Motor, Financial Lines, "
            + "Construction, Marine, Political, Natural Resource and British Marine. ";

    private final AiClient ai;
    private final FeedbackRepository repo;
    private final FeedbackService feedback;

    public AiService(AiClient ai, FeedbackRepository repo, FeedbackService feedback) {
        this.ai = ai;
        this.repo = repo;
        this.feedback = feedback;
    }

    public boolean enabled() {
        return ai.enabled();
    }

    /** Type and severity for a feedback item being logged. */
    public Suggestion classify(String title, String description, String expected, String actual, String lob, String module) {
        if (blank(title) && blank(description)) throw new AiClient.AiException("Write a title or a description first");
        String system = CONTEXT + "Classify one piece of UAT feedback. Types: BUG (the system does something wrong), "
                + "ENHANCEMENT (works as built but should change), UX (wording, layout, usability), CLARIFICATION "
                + "(a question about how it should work), COMMENT (an observation, no action). Severities: CRITICAL "
                + "(blocks a core business process, no workaround, or wrong premium/financial figures), HIGH (major "
                + "function wrong, workaround is painful), MEDIUM (wrong but a reasonable workaround exists), LOW "
                + "(cosmetic or minor). Reply with JSON only: {\"type\":\"…\",\"severity\":\"…\",\"reason\":\"one short sentence\"}.";
        String user = "Title: " + nz(title) + "\nLine of business: " + nz(lob) + "\nWhere: " + nz(module)
                + "\nWhat happened: " + cap(description, 1500) + "\nExpected: " + cap(expected, 600) + "\nActual: " + cap(actual, 600);
        JsonNode j = ai.jsonObject(ai.complete(system, user, 300));
        FeedbackType type = FeedbackType.parse(j.path("type").asText(""), null);
        Severity sev = Severity.parse(j.path("severity").asText(""), null);
        if (type == null || sev == null) throw new AiClient.AiException("The AI reply wasn't in the expected format. Try again.");
        return new Suggestion(type.name(), sev.name(), cap(j.path("reason").asText(""), 300));
    }

    /**
     * Compares an item with other items in the project by meaning, not just shared words. Candidates are
     * the most similar by words plus the most recent, so the prompt stays small.
     */
    public List<DuplicateHit> duplicates(Feedback f) {
        List<Feedback> all = repo.findByProjectOrderByIdDesc(f.getProject()).stream()
                .filter(x -> !x.getId().equals(f.getId()) && x.getDuplicateOf() == null).toList();
        LinkedHashMap<Long, Feedback> pool = new LinkedHashMap<>();
        for (Feedback x : feedback.similar(f.getProject(), f.getTitle() + " " + nz(f.getDescription()), f.getId(), 25)) pool.put(x.getId(), x);
        for (Feedback x : all) { if (pool.size() >= 40) break; pool.putIfAbsent(x.getId(), x); }
        if (pool.isEmpty()) return List.of();
        StringBuilder list = new StringBuilder();
        for (Feedback x : pool.values()) {
            list.append(x.getCode()).append(" | ").append(x.getTitle()).append(" | ").append(x.getLobDivision())
                    .append(" | ").append(x.getWhere()).append(" | ").append(cap(x.getDescription(), 200)).append("\n");
        }
        String system = CONTEXT + "Decide which existing items describe the same problem or request as the new item, "
                + "even if worded differently. Only include real duplicates or near-duplicates, not items that are merely "
                + "about the same screen. Reply with JSON only: {\"duplicates\":[{\"id\":\"UAT-0042\",\"reason\":\"one short sentence\"}]} "
                + "and an empty list if there are none.";
        String user = "New item " + f.getCode() + ":\nTitle: " + f.getTitle() + "\nWhere: " + f.getWhere() + " (" + f.getLobDivision()
                + ")\nWhat happened: " + cap(f.getDescription(), 1200) + "\nExpected: " + cap(f.getExpected(), 400)
                + "\nActual: " + cap(f.getActual(), 400) + "\n\nExisting items (ID | title | LOB | where | details):\n" + list;
        JsonNode j = ai.jsonObject(ai.complete(system, user, 600));
        List<DuplicateHit> hits = new ArrayList<>();
        for (JsonNode d : j.path("duplicates")) {
            Long id = FeedbackService.parseCode(d.path("id").asText(""));
            Feedback x = id == null ? null : pool.get(id);
            if (x != null) hits.add(new DuplicateHit(x.getId(), x.getCode(), x.getTitle(), x.getStage().getLabel(),
                    cap(d.path("reason").asText(""), 300)));
        }
        return hits;
    }

    /** Drafts a user story with acceptance criteria and saves it on the item for a person to edit. */
    @Transactional
    public String draftStory(Feedback f, AppUser by) {
        String system = CONTEXT + "Turn approved UAT feedback into a user story for the delivery team. Write the story as "
                + "\"As a <role>, I want <capability>, so that <benefit>\", using insurance roles such as underwriter, "
                + "broker, referrer or operations. Then 3 to 6 testable acceptance criteria in Given/When/Then form. "
                + "Stay within what the feedback says; don't invent figures, rules or screens. "
                + "Reply with JSON only: {\"story\":\"…\",\"acceptanceCriteria\":[\"Given … When … Then …\"]}.";
        String user = "Title: " + f.getTitle() + "\nLine of business: " + f.getLobDivision() + "\nWhere: " + f.getWhere()
                + "\nDetails: " + cap(f.getDescription(), 1500) + "\nExpected: " + cap(f.getExpected(), 600)
                + "\nActual today: " + cap(f.getActual(), 600) + "\nQA note: " + cap(f.getQaNote(), 400)
                + "\nBusiness decision: " + (f.getDecision() == null ? "not decided yet" : f.getDecision().getLabel())
                + "\nRationale: " + cap(f.getDecisionRationale(), 600);
        JsonNode j = ai.jsonObject(ai.complete(system, user, 900));
        String story = j.path("story").asText("").trim();
        if (story.isEmpty()) throw new AiClient.AiException("The AI reply wasn't in the expected format. Try again.");
        StringBuilder out = new StringBuilder(story).append("\n\nAcceptance criteria:");
        for (JsonNode c : j.path("acceptanceCriteria")) out.append("\n- ").append(c.asText().trim());
        String text = cap(out.toString(), 4000);
        feedback.saveStory(f, by, text, "Drafted user story with AI");
        return text;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
