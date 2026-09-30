package com.uathub.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    private String currentCycle;

    // Jira mapping for this project
    private String jiraProjectKey;
    private String bugIssueType = "Bug";
    private String storyIssueType = "Story";
    private String jiraComponents;   // comma separated
    private String jiraLabels;       // comma separated, added to every issue
    private String jiraFixVersion;   // used for Defect / Enhancement decisions

    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getCurrentCycle() { return currentCycle; }
    public void setCurrentCycle(String currentCycle) { this.currentCycle = currentCycle; }
    public String getJiraProjectKey() { return jiraProjectKey; }
    public void setJiraProjectKey(String jiraProjectKey) { this.jiraProjectKey = jiraProjectKey; }
    public String getBugIssueType() { return bugIssueType; }
    public void setBugIssueType(String bugIssueType) { this.bugIssueType = bugIssueType; }
    public String getStoryIssueType() { return storyIssueType; }
    public void setStoryIssueType(String storyIssueType) { this.storyIssueType = storyIssueType; }
    public String getJiraComponents() { return jiraComponents; }
    public void setJiraComponents(String jiraComponents) { this.jiraComponents = jiraComponents; }
    public String getJiraLabels() { return jiraLabels; }
    public void setJiraLabels(String jiraLabels) { this.jiraLabels = jiraLabels; }
    public String getJiraFixVersion() { return jiraFixVersion; }
    public void setJiraFixVersion(String jiraFixVersion) { this.jiraFixVersion = jiraFixVersion; }
    public Instant getCreatedAt() { return createdAt; }

    public String issueTypeFor(Decision d) {
        return d == Decision.DEFECT ? blankToDefault(bugIssueType, "Bug") : blankToDefault(storyIssueType, "Story");
    }

    private static String blankToDefault(String v, String def) {
        return v == null || v.isBlank() ? def : v.trim();
    }
}
