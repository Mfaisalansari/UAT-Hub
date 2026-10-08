package com.uathub.domain;

public enum ExecStatus {
    NOT_STARTED("Not started"),
    IN_PROGRESS("In progress"),
    RETEST("Re-test"),
    PASSED("Passed"),
    FAILED("Failed"),
    BLOCKED("Blocked");

    private final String label;

    ExecStatus(String label) { this.label = label; }

    public String getLabel() { return label; }

    public String getCss() { return name().toLowerCase(); }

    public boolean isDone() { return this == PASSED || this == FAILED || this == BLOCKED; }
}
