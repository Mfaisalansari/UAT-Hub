package com.uathub.web;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Used in templates as ${@fmt.when(instant)}. */
@Component("fmt")
public class Fmt {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FULL = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm").withZone(ZoneId.systemDefault());

    public String when(Instant t) {
        if (t == null) return "";
        Duration d = Duration.between(t, Instant.now());
        long min = d.toMinutes();
        if (min < 1) return "just now";
        if (min < 60) return min + " min ago";
        long h = d.toHours();
        if (h < 24) return h + " h ago";
        long days = d.toDays();
        if (days == 1) return "yesterday";
        if (days < 7) return days + " days ago";
        return DAY.format(t);
    }

    public String full(Instant t) {
        return t == null ? "" : FULL.format(t);
    }

    public String code(Long id) {
        return id == null ? "" : String.format("UAT-%04d", id);
    }

    public String size(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return (bytes / 1024) + " KB";
        return String.format("%.1f MB", bytes / (1024.0 * 1024));
    }
}
