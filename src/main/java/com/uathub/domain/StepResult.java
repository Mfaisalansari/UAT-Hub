package com.uathub.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
public class StepResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Execution execution;

    @ManyToOne(optional = false)
    private ScenarioStep step;

    private int attempt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private StepStatus result;

    @Column(length = 2000)
    private String actual;

    @ManyToOne
    private AppUser recordedBy;

    private Instant recordedAt = Instant.now();

    public Long getId() { return id; }
    public Execution getExecution() { return execution; }
    public void setExecution(Execution execution) { this.execution = execution; }
    public ScenarioStep getStep() { return step; }
    public void setStep(ScenarioStep step) { this.step = step; }
    public int getAttempt() { return attempt; }
    public void setAttempt(int attempt) { this.attempt = attempt; }
    public StepStatus getResult() { return result; }
    public void setResult(StepStatus result) { this.result = result; }
    public String getActual() { return actual; }
    public void setActual(String actual) { this.actual = actual; }
    public AppUser getRecordedBy() { return recordedBy; }
    public void setRecordedBy(AppUser recordedBy) { this.recordedBy = recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Instant recordedAt) { this.recordedAt = recordedAt; }
}
