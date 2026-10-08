package com.uathub.security;

import com.uathub.domain.Initials;
import com.uathub.domain.Role;

import java.io.Serializable;

/** The signed-in person, rebuilt from the access cookie on every request. */
public record CurrentUser(Long id, String name, Role role) implements Serializable {

    public boolean isAdmin() { return role == Role.ADMIN; }

    public boolean canLog() { return role != Role.BUSINESS; }

    public boolean canTriage() { return role == Role.QA_LEAD || role == Role.ADMIN; }

    public boolean canDecide() { return role == Role.BUSINESS || role == Role.ADMIN; }

    public boolean canPush() { return canTriage(); }

    /** Everyone can run scenarios assigned to them, including Business reviewers. */
    public boolean canExecute() { return true; }

    /** Scenario library, test runs, assignment and new builds. */
    public boolean canManageTests() { return canTriage(); }

    public boolean canSignOff() { return role == Role.BUSINESS || role == Role.ADMIN; }

    public String initials() { return Initials.of(name); }

    public String roleLabel() { return role.getLabel(); }
}
