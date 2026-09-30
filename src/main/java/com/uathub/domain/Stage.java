package com.uathub.domain;

/** Where a feedback item sits in the flow. step drives the five-dot rail in the UI. */
public enum Stage {
    LOGGED("Logged", 0),
    NEEDS_INFO("Needs info", 1),
    BUSINESS_REVIEW("Business review", 2),
    DECIDED("Decided", 3),
    IN_JIRA("In Jira", 4),
    CLOSED("Closed", 4);

    private final String label;
    private final int step;

    Stage(String label, int step) {
        this.label = label;
        this.step = step;
    }

    public String getLabel() { return label; }

    public int getStep() { return step; }

    public String getCss() { return name().toLowerCase(); }

    public boolean isOpenForTriage() { return this == LOGGED || this == NEEDS_INFO; }

    public boolean isEditable() { return this == LOGGED || this == NEEDS_INFO || this == BUSINESS_REVIEW; }
}
