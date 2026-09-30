package com.uathub.domain;

public enum Severity {
    CRITICAL("Critical"),
    HIGH("High"),
    MEDIUM("Medium"),
    LOW("Low");

    private final String label;

    Severity(String label) { this.label = label; }

    public String getLabel() { return label; }

    public String getCss() { return name().toLowerCase(); }

    public static Severity parse(String s, Severity fallback) {
        if (s == null || s.isBlank()) return fallback;
        String v = s.trim().toUpperCase();
        for (Severity x : values()) {
            if (x.name().equals(v)) return x;
        }
        if (v.startsWith("P1") || v.startsWith("BLOCK")) return CRITICAL;
        if (v.startsWith("P2")) return HIGH;
        if (v.startsWith("P3")) return MEDIUM;
        if (v.startsWith("P4")) return LOW;
        return fallback;
    }
}
