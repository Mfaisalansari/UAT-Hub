package com.uathub.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
public class Feedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Project project;

    private String cycle;
    private String lob;
    private String division;
    private String module;   // Pega case type > stage
    private String screen;

    @Enumerated(EnumType.STRING)
    private FeedbackType type = FeedbackType.BUG;

    @Enumerated(EnumType.STRING)
    private Severity severity = Severity.MEDIUM;

    @Column(nullable = false, length = 300)
    private String title;

    @Column(length = 4000)
    private String description;
    @Column(length = 2000)
    private String expected;
    @Column(length = 2000)
    private String actual;
    @Column(length = 2000)
    private String qaNote;

    @ManyToOne
    private AppUser raisedBy;

    @Enumerated(EnumType.STRING)
    private Stage stage = Stage.LOGGED;

    @Enumerated(EnumType.STRING)
    private Decision decision;
    @Column(length = 2000)
    private String decisionRationale;
    private String targetRelease;
    @ManyToOne
    private AppUser decidedBy;
    private Instant decidedAt;

    private String jiraKey;
    private String jiraUrl;
    private Long duplicateOf;

    /** Build under test when the issue was found while running a scenario. */
    private String foundInBuild;
    /** Set when QA deploys a build that fixes it; linked scenarios then go back for re-test. */
    private String fixedInBuild;

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    /** Human-friendly ID shown everywhere, e.g. UAT-0042. */
    public String getCode() {
        return id == null ? "UAT-new" : String.format("UAT-%04d", id);
    }

    public String getWhere() {
        StringBuilder sb = new StringBuilder();
        if (module != null && !module.isBlank()) sb.append(module.trim());
        if (screen != null && !screen.isBlank()) {
            if (!sb.isEmpty()) sb.append(" › ");
            sb.append(screen.trim());
        }
        return sb.toString();
    }

    public String getLobDivision() {
        String l = lob == null ? "" : lob;
        String d = division == null ? "" : division;
        if (l.isBlank()) return d;
        return d.isBlank() ? l : l + " · " + d;
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }
    public String getCycle() { return cycle; }
    public void setCycle(String cycle) { this.cycle = cycle; }
    public String getLob() { return lob; }
    public void setLob(String lob) { this.lob = lob; }
    public String getDivision() { return division; }
    public void setDivision(String division) { this.division = division; }
    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }
    public String getScreen() { return screen; }
    public void setScreen(String screen) { this.screen = screen; }
    public FeedbackType getType() { return type; }
    public void setType(FeedbackType type) { this.type = type; }
    public Severity getSeverity() { return severity; }
    public void setSeverity(Severity severity) { this.severity = severity; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getExpected() { return expected; }
    public void setExpected(String expected) { this.expected = expected; }
    public String getActual() { return actual; }
    public void setActual(String actual) { this.actual = actual; }
    public String getQaNote() { return qaNote; }
    public void setQaNote(String qaNote) { this.qaNote = qaNote; }
    public AppUser getRaisedBy() { return raisedBy; }
    public void setRaisedBy(AppUser raisedBy) { this.raisedBy = raisedBy; }
    public Stage getStage() { return stage; }
    public void setStage(Stage stage) { this.stage = stage; }
    public Decision getDecision() { return decision; }
    public void setDecision(Decision decision) { this.decision = decision; }
    public String getDecisionRationale() { return decisionRationale; }
    public void setDecisionRationale(String decisionRationale) { this.decisionRationale = decisionRationale; }
    public String getTargetRelease() { return targetRelease; }
    public void setTargetRelease(String targetRelease) { this.targetRelease = targetRelease; }
    public AppUser getDecidedBy() { return decidedBy; }
    public void setDecidedBy(AppUser decidedBy) { this.decidedBy = decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public void setDecidedAt(Instant decidedAt) { this.decidedAt = decidedAt; }
    public String getJiraKey() { return jiraKey; }
    public void setJiraKey(String jiraKey) { this.jiraKey = jiraKey; }
    public String getJiraUrl() { return jiraUrl; }
    public void setJiraUrl(String jiraUrl) { this.jiraUrl = jiraUrl; }
    public Long getDuplicateOf() { return duplicateOf; }
    public void setDuplicateOf(Long duplicateOf) { this.duplicateOf = duplicateOf; }
    public String getFoundInBuild() { return foundInBuild; }
    public void setFoundInBuild(String foundInBuild) { this.foundInBuild = foundInBuild; }
    public String getFixedInBuild() { return fixedInBuild; }
    public void setFixedInBuild(String fixedInBuild) { this.fixedInBuild = fixedInBuild; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
