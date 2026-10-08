package com.uathub.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** Business sign-off of one line of business for a whole UAT cycle. */
@Entity
@Table(uniqueConstraints = @UniqueConstraint(columnNames = {"uat_cycle_id", "lob"}))
public class CycleSignOff {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private UatCycle uatCycle;

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
    public UatCycle getUatCycle() { return uatCycle; }
    public void setUatCycle(UatCycle uatCycle) { this.uatCycle = uatCycle; }
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
