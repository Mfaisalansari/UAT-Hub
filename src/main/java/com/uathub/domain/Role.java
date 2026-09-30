package com.uathub.domain;

public enum Role {
    TESTER("Tester"),
    QA_LEAD("QA lead"),
    BUSINESS("Business reviewer"),
    ADMIN("Admin");

    private final String label;

    Role(String label) { this.label = label; }

    public String getLabel() { return label; }
}
