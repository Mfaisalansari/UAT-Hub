package com.uathub.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** Business sign-off of one line of business within a test run. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"run_id", "lob"}))
public class LobSignOff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private TestRun run;

    @Column(nullable = false)
    private String lob;

    @ManyToOne
    private AppUser signedBy;

    @Column(length = 2000)
    private String note;

    /** Open issues accepted at sign-off, e.g. "UAT-0135, UAT-0143". */
    @Column(length = 1000)
    private String acceptedIssues;

    private Instant signedAt = Instant.now();

    public Long getId() { return id; }
    public TestRun getRun() { return run; }
    public void setRun(TestRun run) { this.run = run; }
    public String getLob() { return lob; }
    public void setLob(String lob) { this.lob = lob; }
    public AppUser getSignedBy() { return signedBy; }
    public void setSignedBy(AppUser signedBy) { this.signedBy = signedBy; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getAcceptedIssues() { return acceptedIssues; }
    public void setAcceptedIssues(String acceptedIssues) { this.acceptedIssues = acceptedIssues; }
    public Instant getSignedAt() { return signedAt; }
}
