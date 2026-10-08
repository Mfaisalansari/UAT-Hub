package com.uathub.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String email;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    /** Secret part of the personal link. Regenerating it invalidates old links and cookies. */
    @Column(nullable = false, unique = true, length = 64)
    private String accessToken;

    private boolean active = true;

    /** Email notifications on (null counts as on). Wrapper type so the column can be added to existing data. */
    private Boolean notifyEmail;

    /** BCrypt hash of the optional 4-digit PIN. */
    private String pinHash;

    private Instant createdAt = Instant.now();
    private Instant lastSeenAt;
    private Instant linkOpenedAt;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_project",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "project_id"))
    private Set<Project> projects = new HashSet<>();

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
    public boolean isNotifyEmail() { return notifyEmail == null || notifyEmail; }
    public void setNotifyEmail(boolean notifyEmail) { this.notifyEmail = notifyEmail; }
    public boolean isEmailable() { return active && email != null && !email.isBlank() && isNotifyEmail(); }
    public String getPinHash() { return pinHash; }
    public void setPinHash(String pinHash) { this.pinHash = pinHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }
    public Instant getLinkOpenedAt() { return linkOpenedAt; }
    public void setLinkOpenedAt(Instant linkOpenedAt) { this.linkOpenedAt = linkOpenedAt; }
    public Set<Project> getProjects() { return projects; }

    public boolean canAccess(Project p) {
        if (p == null) return false;
        return role == Role.ADMIN || projects.stream().anyMatch(x -> x.getId().equals(p.getId()));
    }

    public String getInitials() {
        return Initials.of(name);
    }

    public String getStatus() {
        if (!active) return "Revoked";
        return linkOpenedAt == null ? "Waiting" : "Active";
    }
}
