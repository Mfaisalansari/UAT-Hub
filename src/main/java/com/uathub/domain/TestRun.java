package com.uathub.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** One round of UAT testing on a build, e.g. "UAT cycle 3" on "Build 4.2.1". */
@Entity
public class TestRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Project project;

    @Column(nullable = false)
    private String name;

    /** The build or version currently deployed for this run (free text). */
    private String build;

    private boolean active = true;

    @ManyToOne
    private AppUser createdBy;

    private Instant createdAt = Instant.now();

    public String getLabel() {
        return build == null || build.isBlank() ? name : name + " · " + build;
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBuild() { return build; }
    public void setBuild(String build) { this.build = build; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public AppUser getCreatedBy() { return createdBy; }
    public void setCreatedBy(AppUser createdBy) { this.createdBy = createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
