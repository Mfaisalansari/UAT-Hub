package com.uathub.domain;

import jakarta.persistence.*;

import java.time.Instant;

/** One message in a feedback item's discussion. */
@Entity
@Table(name = "feedback_comment")
public class Comment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(optional = false)
    private Feedback feedback;

    @ManyToOne
    private AppUser author;

    @Column(nullable = false, length = 2000)
    private String text;

    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Feedback getFeedback() { return feedback; }
    public void setFeedback(Feedback feedback) { this.feedback = feedback; }
    public AppUser getAuthor() { return author; }
    public void setAuthor(AppUser author) { this.author = author; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Instant getCreatedAt() { return createdAt; }
}
