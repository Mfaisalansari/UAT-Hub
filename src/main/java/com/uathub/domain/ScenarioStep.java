package com.uathub.domain;

import jakarta.persistence.*;

@Entity
public class ScenarioStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Scenario scenario;

    private int stepNo;

    @Column(nullable = false, length = 1000)
    private String action;

    @Column(length = 1000)
    private String expected;

    public Long getId() { return id; }
    public Scenario getScenario() { return scenario; }
    public void setScenario(Scenario scenario) { this.scenario = scenario; }
    public int getStepNo() { return stepNo; }
    public void setStepNo(int stepNo) { this.stepNo = stepNo; }
    public String getAction() { return action; }
    public void setAction(String action) { this.action = action; }
    public String getExpected() { return expected; }
    public void setExpected(String expected) { this.expected = expected; }
}
