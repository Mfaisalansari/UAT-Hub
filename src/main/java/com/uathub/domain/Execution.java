package com.uathub.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** One test case (a scenario, or one of its data sets) assigned to a person within a test run. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"run_id", "scenario_id", "data_set_id"}))
public class Execution {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private TestRun run;

    @ManyToOne(optional = false)
    private Scenario scenario;

    /** Which data set this case runs with; null when the scenario has none. */
    @ManyToOne
    private ScenarioDataSet dataSet;

    @ManyToOne
    private AppUser assignee;

    @Enumerated(EnumType.STRING)
    private ExecStatus status = ExecStatus.NOT_STARTED;

    /** 1 for the first run-through; each re-test adds one. Step results are kept per attempt. */
    private int attempt = 1;

    /** Build being verified when status is RETEST. */
    private String retestBuild;

    private Instant startedAt;
    private Instant finishedAt;
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() { updatedAt = Instant.now(); }

    public boolean isRetest() { return attempt > 1; }

    /** "SC-014" or "SC-014 · Broker portal". */
    public String getCaseCode() {
        return dataSet == null ? scenario.getCode() : scenario.getCode() + " · " + dataSet.getName();
    }

    /** Key that identifies the test case across runs of a cycle. */
    public String getCaseKey() {
        return scenario.getId() + ":" + (dataSet == null ? 0 : dataSet.getId());
    }

    public Long getId() { return id; }
    public TestRun getRun() { return run; }
    public void setRun(TestRun run) { this.run = run; }
    public Scenario getScenario() { return scenario; }
    public ScenarioDataSet getDataSet() { return dataSet; }
    public void setDataSet(ScenarioDataSet dataSet) { this.dataSet = dataSet; }
    public void setScenario(Scenario scenario) { this.scenario = scenario; }
    public AppUser getAssignee() { return assignee; }
    public void setAssignee(AppUser assignee) { this.assignee = assignee; }
    public ExecStatus getStatus() { return status; }
    public void setStatus(ExecStatus status) { this.status = status; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public String getRetestBuild() { return retestBuild; }
    public void setRetestBuild(String retestBuild) { this.retestBuild = retestBuild; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinishedAt() { return finishedAt; }
    public void setFinishedAt(Instant finishedAt) { this.finishedAt = finishedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
