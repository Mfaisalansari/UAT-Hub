package com.uathub.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** A reusable UAT test scenario: preconditions plus numbered steps with expected results. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "code"}))
public class Scenario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Project project;

    /** Human ID, e.g. SC-014. Unique within a project. */
    @Column(nullable = false, length = 40)
    private String code;

    @Column(nullable = false, length = 300)
    private String title;

    private String lob;
    private String division;
    private String module;

    @Enumerated(EnumType.STRING)
    private Priority priority = Priority.MEDIUM;

    @Column(length = 2000)
    private String preconditions;

    @Column(length = 2000)
    private String testData;

    private boolean active = true;

    @OneToMany(mappedBy = "scenario", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepNo ASC")
    private List<ScenarioStep> steps = new ArrayList<>();

    private Instant createdAt = Instant.now();
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    /** Updates steps in place (so recorded results stay attached), adds new ones, drops extra ones. */
    public void replaceSteps(List<String[]> actionExpected, java.util.function.Predicate<ScenarioStep> removable) {
        for (int i = 0; i < actionExpected.size(); i++) {
            ScenarioStep s;
            if (i < steps.size()) {
                s = steps.get(i);
            } else {
                s = new ScenarioStep();
                s.setScenario(this);
                steps.add(s);
            }
            s.setStepNo(i + 1);
            s.setAction(actionExpected.get(i)[0]);
            s.setExpected(actionExpected.get(i)[1]);
        }
        while (steps.size() > actionExpected.size()) {
            ScenarioStep last = steps.get(steps.size() - 1);
            if (!removable.test(last)) {
                throw new IllegalArgumentException("Step " + last.getStepNo() + " already has test results, so it can't be removed. "
                        + "Reword it instead, or archive this scenario and create a new one.");
            }
            steps.remove(steps.size() - 1);
        }
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
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getLob() { return lob; }
    public void setLob(String lob) { this.lob = lob; }
    public String getDivision() { return division; }
    public void setDivision(String division) { this.division = division; }
    public String getModule() { return module; }
    public void setModule(String module) { this.module = module; }
    public Priority getPriority() { return priority; }
    public void setPriority(Priority priority) { this.priority = priority; }
    public String getPreconditions() { return preconditions; }
    public void setPreconditions(String preconditions) { this.preconditions = preconditions; }
    public String getTestData() { return testData; }
    public void setTestData(String testData) { this.testData = testData; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public List<ScenarioStep> getSteps() { return steps; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
