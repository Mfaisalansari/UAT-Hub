package com.uathub.service;

import com.uathub.config.UatHubProperties;
import com.uathub.domain.Attachment;
import com.uathub.domain.Feedback;
import com.uathub.domain.StepResult;
import com.uathub.repo.AttachmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/**
 * Files live next to the database file:
 *   data/attachments/{feedbackId}/          for feedback items
 *   data/attachments/steps/{stepResultId}/  for evidence captured on a scenario step
 */
@Service
public class AttachmentStorage {

    private final Path root;
    private final AttachmentRepository repo;

    public AttachmentStorage(UatHubProperties props, AttachmentRepository repo) {
        this.root = Path.of(props.dataDir(), "attachments").toAbsolutePath().normalize();
        this.repo = repo;
    }

    public void saveAll(Feedback feedback, List<MultipartFile> files) {
        if (files == null) return;
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            save(feedback, f);
        }
    }

    public Attachment save(Feedback feedback, MultipartFile file) {
        Attachment a = new Attachment();
        a.setFeedback(feedback);
        return store(a, file);
    }

    public void saveAll(StepResult stepResult, List<MultipartFile> files) {
        if (files == null) return;
        for (MultipartFile f : files) {
            if (f == null || f.isEmpty()) continue;
            Attachment a = new Attachment();
            a.setStepResult(stepResult);
            store(a, f);
        }
    }

    /** Copies step evidence onto a feedback item when an issue is raised from that step. */
    public void copyToFeedback(List<Attachment> evidence, Feedback feedback) {
        for (Attachment src : evidence) {
            Attachment a = new Attachment();
            a.setFeedback(feedback);
            a.setFileName(src.getFileName());
            a.setContentType(src.getContentType());
            a.setSize(src.getSize());
            a.setStoredName(UUID.randomUUID().toString().substring(0, 8) + "-" + src.getFileName());
            Path target = dirFor(a).resolve(a.getStoredName());
            try {
                Files.createDirectories(target.getParent());
                Files.copy(pathOf(src), target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException("Could not copy " + src.getFileName(), e);
            }
            repo.save(a);
        }
    }

    public Path pathOf(Attachment a) {
        Path p = dirFor(a).resolve(a.getStoredName()).normalize();
        if (!p.startsWith(root)) throw new IllegalStateException("Invalid attachment path");
        return p;
    }

    public List<Attachment> list(Feedback feedback) {
        return repo.findByFeedbackOrderByIdAsc(feedback);
    }

    public List<Attachment> list(StepResult stepResult) {
        return repo.findByStepResultOrderByIdAsc(stepResult);
    }

    private Attachment store(Attachment a, MultipartFile file) {
        String original = clean(file.getOriginalFilename());
        a.setFileName(original);
        a.setContentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        a.setSize(file.getSize());
        a.setStoredName(UUID.randomUUID().toString().substring(0, 8) + "-" + original);
        Path dir = dirFor(a);
        try (InputStream in = file.getInputStream()) {
            Files.createDirectories(dir);
            Files.copy(in, dir.resolve(a.getStoredName()), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store " + original, e);
        }
        return repo.save(a);
    }

    private Path dirFor(Attachment a) {
        if (a.getFeedback() != null) return root.resolve(String.valueOf(a.getFeedback().getId()));
        return root.resolve("steps").resolve(String.valueOf(a.getStepResult().getId()));
    }

    private static String clean(String name) {
        if (name == null || name.isBlank()) return "file";
        String base = Path.of(name).getFileName().toString();
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return base.length() > 80 ? base.substring(base.length() - 80) : base;
    }
}
