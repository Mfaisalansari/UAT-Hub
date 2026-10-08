package com.uathub.service;

import com.uathub.domain.AppUser;
import com.uathub.domain.Comment;
import com.uathub.domain.Feedback;
import com.uathub.notify.Notifier;
import com.uathub.repo.AppUserRepository;
import com.uathub.repo.CommentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class CommentService {

    private final CommentRepository comments;
    private final AppUserRepository users;
    private final Notifier notifier;

    public CommentService(CommentRepository comments, AppUserRepository users, Notifier notifier) {
        this.comments = comments;
        this.users = users;
        this.notifier = notifier;
    }

    public List<Comment> thread(Feedback f) {
        return comments.findByFeedbackOrderByCreatedAtAsc(f);
    }

    /** People who can see the item: the ones a comment can mention. */
    public List<AppUser> mentionable(Feedback f) {
        return users.findAllByOrderByActiveDescNameAsc().stream()
                .filter(AppUser::isActive)
                .filter(u -> u.canAccess(f.getProject()))
                .sorted(Comparator.comparing(AppUser::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * Adds a comment. Notifies everyone @mentioned by name, the person who raised the item and earlier
     * commenters, so a conversation reaches the people in it.
     */
    @Transactional
    public Comment add(Feedback f, AppUser by, String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Write a comment first");
        String body = text.trim();
        if (body.length() > 2000) throw new IllegalArgumentException("Keep comments under 2,000 characters");
        List<Comment> earlier = thread(f);
        Comment c = new Comment();
        c.setFeedback(f);
        c.setAuthor(by);
        c.setText(body);
        c = comments.save(c);

        Set<AppUser> to = new LinkedHashSet<>(mentioned(f, body));
        boolean anyMention = !to.isEmpty();
        if (f.getRaisedBy() != null) to.add(f.getRaisedBy());
        for (Comment e : earlier) if (e.getAuthor() != null) to.add(e.getAuthor());
        notifier.send(f.getProject(), to, by,
                (anyMention ? by.getName() + " mentioned you on " : "New comment on ") + f.getCode(),
                f.getTitle() + "\n\n" + by.getName() + " wrote:\n" + body, "/feedback/" + f.getId() + "#discussion", false);
        return c;
    }

    /** Longest names first, so "@Anita Rao" isn't also read as a mention of someone called "Anita". */
    private List<AppUser> mentioned(Feedback f, String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        List<AppUser> candidates = new ArrayList<>(mentionable(f));
        candidates.sort(Comparator.comparingInt((AppUser u) -> -u.getName().length()));
        List<AppUser> out = new ArrayList<>();
        for (AppUser u : candidates) {
            String tag = "@" + u.getName().toLowerCase(Locale.ROOT);
            int i = lower.indexOf(tag);
            if (i < 0) continue;
            int end = i + tag.length();
            boolean wholeName = end >= lower.length() || !Character.isLetterOrDigit(lower.charAt(end));
            if (wholeName) {
                out.add(u);
                lower = lower.replace(tag, " ");
            }
        }
        return out;
    }
}
