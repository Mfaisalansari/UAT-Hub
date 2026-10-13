package com.uathub.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A UAT test scenario: preconditions plus numbered steps with expected results. It belongs to one UAT cycle;
 * reusing it in a later cycle makes an independent copy, so each cycle keeps the version it tested.
 * Optional test data sets turn one scenario into several test cases (one per channel, user or data).
 */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"uat_cycle_id", "code"}))
public class Scenario {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Project project;

    /** The UAT cycle whose library this scenario is in. */
    @ManyToOne
    private UatCycle uatCycle;

    /** Code of the scenario this one was copied from, e.g. "SC-014 (October 2026 release)". */
    private String copiedFrom;

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

    /** Test data common to every data set (or the only test data when there are no data sets). */
    @Column(length = 2000)
    private String testData;

    @OneToMany(mappedBy = "scenario", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<ScenarioDataSet> dataSets = new ArrayList<>();

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

    /** Updates data sets in place by position (keeping their results), adds new ones, drops extra ones if unused. */
    public void replaceDataSets(List<String[]> rows, java.util.function.Predicate<ScenarioDataSet> removable) {
        for (int i = 0; i < rows.size(); i++) {
            ScenarioDataSet d;
            if (i < dataSets.size()) {
                d = dataSets.get(i);
            } else {
                d = new ScenarioDataSet();
                d.setScenario(this);
                dataSets.add(d);
            }
            String[] r = rows.get(i);
            d.setPosition(i + 1);
            d.setName(r[0]);
            d.setChannel(r[1]);
            d.setPersona(r[2]);
            d.setTestData(r[3]);
        }
        while (dataSets.size() > rows.size()) {
            ScenarioDataSet last = dataSets.get(dataSets.size() - 1);
            if (!removable.test(last)) {
                throw new IllegalArgumentException("Data set \"" + last.getName() + "\" is already in a test run, so it can't be removed. "
                        + "Rename it instead, or archive this scenario and create a new one.");
            }
            dataSets.remove(dataSets.size() - 1);
        }
    }

    /** Test cases this scenario produces in a run: one per data set, or one when it has none. */
    public int getCaseCount() {
        return dataSets.isEmpty() ? 1 : dataSets.size();
    }

    public String getLobDivision() {
        String l = lob == null ? "" : lob;
        String d = division == null ? "" : division;
        if (l.isBlank()) return d;
        return d.isBlank() ? l : l + " · " + d;
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public UatCycle getUatCycle() { return uatCycle; }
    public void setUatCycle(UatCycle uatCycle) { this.uatCycle = uatCycle; }
    public String getCopiedFrom() { return copiedFrom; }
    public void setCopiedFrom(String copiedFrom) { this.copiedFrom = copiedFrom; }
    public List<ScenarioDataSet> getDataSets() { return dataSets; }
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
