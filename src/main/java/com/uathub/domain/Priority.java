package com.uathub.domain;

public enum Priority {
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low");

    private final String label;

    Priority(String label) { this.label = label; }

    public String getLabel() { return label; }

    public String getCss() { return name().toLowerCase(); }

    public static Priority parse(String s, Priority fallback) {
        if (s == null || s.isBlank()) return fallback;
        String v = s.trim().toUpperCase();
        for (Priority p : values()) if (p.name().equals(v)) return p;
        if (v.startsWith("P1") || v.startsWith("CRIT")) return HIGH;
        if (v.startsWith("P2")) return MEDIUM;
        if (v.startsWith("P3") || v.startsWith("P4")) return LOW;
        return fallback;
    }
}
