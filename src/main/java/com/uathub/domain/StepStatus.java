package com.uathub.domain;

public enum StepStatus {
    PASS("Pass"),
    FAIL("Fail"),
    BLOCKED("Blocked"),
    NA("N/A");

    private final String label;

    StepStatus(String label) { this.label = label; }

    public String getLabel() { return label; }

    public String getCss() { return name().toLowerCase(); }
}
