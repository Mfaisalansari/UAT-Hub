package com.uathub.domain;

public enum FeedbackType {
    BUG("Bug"),
    ENHANCEMENT("Enhancement"),
    UX("UX"),
    CLARIFICATION("Clarification"),
    COMMENT("Comment");

    private final String label;

    FeedbackType(String label) { this.label = label; }

    public String getLabel() { return label; }

    public String getCss() { return name().toLowerCase(); }

    public static FeedbackType parse(String s, FeedbackType fallback) {
        if (s == null || s.isBlank()) return fallback;
        String v = s.trim().toUpperCase().replace(' ', '_');
        for (FeedbackType t : values()) {
            if (t.name().equals(v) || t.label.equalsIgnoreCase(s.trim())) return t;
        }
        if (v.startsWith("DEFECT")) return BUG;
        if (v.startsWith("CR") || v.contains("CHANGE")) return ENHANCEMENT;
        return fallback;
    }
}
