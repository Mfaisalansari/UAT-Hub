package com.uathub.domain;

public final class Initials {
    private Initials() {}

    public static String of(String name) {
        if (name == null || name.isBlank()) return "?";
        String[] parts = name.trim().split("\\s+");
        String a = parts[0].substring(0, 1);
        String b = parts.length > 1 ? parts[parts.length - 1].substring(0, 1) : "";
        return (a + b).toUpperCase();
    }
}
