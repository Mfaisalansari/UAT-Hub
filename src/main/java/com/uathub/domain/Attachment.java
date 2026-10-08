package com.uathub.domain;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Set for attachments on a feedback item. */
    @ManyToOne
    private Feedback feedback;

    /** Set for evidence captured on a scenario step. */
    @ManyToOne
    private StepResult stepResult;

    private String fileName;
    private String contentType;
    private long size;
    /** File name on disk inside data/attachments/{feedbackId}/ */
    private String storedName;
    private Instant createdAt = Instant.now();

    public Long getId() { return id; }
    public Feedback getFeedback() { return feedback; }
    public void setFeedback(Feedback feedback) { this.feedback = feedback; }
    public StepResult getStepResult() { return stepResult; }
    public void setStepResult(StepResult stepResult) { this.stepResult = stepResult; }

    public Project getProject() {
        if (feedback != null) return feedback.getProject();
        return stepResult == null ? null : stepResult.getExecution().getRun().getProject();
    }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }
    public long getSize() { return size; }
    public void setSize(long size) { this.size = size; }
    public String getStoredName() { return storedName; }
    public void setStoredName(String storedName) { this.storedName = storedName; }
    public Instant getCreatedAt() { return createdAt; }

    public boolean isImage() {
        return contentType != null && contentType.startsWith("image/");
    }
}
