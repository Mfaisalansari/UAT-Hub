package com.uathub.web;

import com.uathub.domain.FeedbackType;
import com.uathub.domain.Severity;

/** Fields posted by the log / edit forms. */
public record FeedbackForm(
        String title,
        String description,
        String expected,
        String actual,
        String module,
        String screen,
        String lob,
        String division,
        FeedbackType type,
        Severity severity) {
}
