package com.uathub.domain;

import jakarta.persistence.*;

/**
 * One way to run a scenario: a channel, the user or role to log in as, and the test data to use.
 * Each data set becomes its own test case in a run, so it can go to a different tester.
 */
@Entity
public class ScenarioDataSet {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Scenario scenario;

    private int position;

    /** Short label shown everywhere, e.g. "Broker portal – UK broker". */
    @Column(nullable = false, length = 120)
    private String name;

    private String channel;

    /** Who to log in as, e.g. "Underwriter (UK)" or "Broker – ABC Insurance Ltd". Never a password. */
    private String persona;

    @Column(length = 2000)
    private String testData;

    public Long getId() { return id; }
    public Scenario getScenario() { return scenario; }
    public void setScenario(Scenario scenario) { this.scenario = scenario; }
    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }
    public String getTestData() { return testData; }
    public void setTestData(String testData) { this.testData = testData; }
}
