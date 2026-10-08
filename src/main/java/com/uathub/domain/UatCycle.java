package com.uathub.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** A UAT window within a project, e.g. "October 2026 release". Holds its own runs, feedback and sign-offs. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"project_id", "name"}))
public class UatCycle {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Project project;

    @Column(nullable = false)
    private String name;

    /** Release or build line this UAT window is for (free text). */
    private String releaseName;

    private LocalDate startDate;
    private LocalDate endDate;

    /** Open cycles take new feedback and runs; closed ones are kept read-only for reference. */
    private boolean open = true;

    private Instant createdAt = Instant.now();

    public String getDates() {
        if (startDate == null && endDate == null) return "";
        if (endDate == null) return "From " + DAY.format(startDate);
        if (startDate == null) return "Until " + DAY.format(endDate);
        return DAY.format(startDate) + " – " + DAY.format(endDate);
    }

    public Long getId() { return id; }
    public Project getProject() { return project; }
    public void setProject(Project project) { this.project = project; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getReleaseName() { return releaseName; }
    public void setReleaseName(String releaseName) { this.releaseName = releaseName; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getEndDate() { return endDate; }
    public void setEndDate(LocalDate endDate) { this.endDate = endDate; }
    public boolean isOpen() { return open; }
    public void setOpen(boolean open) { this.open = open; }
    public Instant getCreatedAt() { return createdAt; }
}
