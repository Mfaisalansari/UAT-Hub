package com.uathub.domain;

public enum Decision {
    DEFECT("Defect", "Fix in this release", true),
    ENHANCEMENT("Enhancement", "Change request, this release", true),
    FUTURE_RELEASE("Future release", "Park in backlog", true),
    COMMENT("Comment only", "Noted, no action", false),
    REJECTED("Works as designed", "Close with reason", false);

    private final String label;
    private final String hint;
    private final boolean goesToJira;

    Decision(String label, String hint, boolean goesToJira) {
        this.label = label;
        this.hint = hint;
        this.goesToJira = goesToJira;
    }

    public String getLabel() { return label; }

    public String getHint() { return hint; }

    public boolean isGoesToJira() { return goesToJira; }

    public String getCss() { return name().toLowerCase(); }
}
