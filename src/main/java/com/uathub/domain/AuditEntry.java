package com.uathub.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** Every change to a feedback item: who, what, when. */
@Entity
public class AuditEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Feedback feedback;

    @ManyToOne
    private AppUser actor;

    private String action;

    @Column(length = 1000)
    private String detail;

    @Column(name = "happened_at")
    private Instant at = Instant.now();

    public AuditEntry() {}

    public AuditEntry(Feedback feedback, AppUser actor, String action, String detail) {
        this.feedback = feedback;
        this.actor = actor;
        this.action = action;
        this.detail = detail;
    }

    public Long getId() { return id; }
    public Feedback getFeedback() { return feedback; }
    public AppUser getActor() { return actor; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
    public Instant getAt() { return at; }
}
