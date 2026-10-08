package com.uathub.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** Ties a feedback item (issue) to the scenario run and step where it was found. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"feedback_id", "execution_id"}))
public class IssueLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Feedback feedback;

    @ManyToOne(optional = false)
    private Execution execution;

    private Integer stepNo;

    private Instant createdAt = Instant.now();

    public IssueLink() {}

    public IssueLink(Feedback feedback, Execution execution, Integer stepNo) {
        this.feedback = feedback;
        this.execution = execution;
        this.stepNo = stepNo;
    }

    public Long getId() { return id; }
    public Feedback getFeedback() { return feedback; }
    public Execution getExecution() { return execution; }
    public Integer getStepNo() { return stepNo; }
    public Instant getCreatedAt() { return createdAt; }
}
