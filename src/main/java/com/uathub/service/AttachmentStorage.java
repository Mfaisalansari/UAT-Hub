package com.uathub.service;

import com.uathub.config.UatHubProperties;
import com.uathub.domain.Attachment;
import com.uathub.domain.Feedback;
import com.uathub.repo.AttachmentRepository;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;

/** Screenshots and files live next to the database file: data/attachments/{feedbackId}/ */
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
        String original = clean(file.getOriginalFilename());
        String stored = UUID.randomUUID().toString().substring(0, 8) + "-" + original;
        Path dir = dirFor(feedback);
        try {
            Files.createDirectories(dir);
            Files.copy(file.getInputStream(), dir.resolve(stored), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not store " + original, e);
        }
        Attachment a = new Attachment();
        a.setFeedback(feedback);
        a.setFileName(original);
        a.setContentType(file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        a.setSize(file.getSize());
        a.setStoredName(stored);
        return repo.save(a);
    }

    public Path pathOf(Attachment a) {
        Path p = dirFor(a.getFeedback()).resolve(a.getStoredName()).normalize();
        if (!p.startsWith(root)) throw new IllegalStateException("Invalid attachment path");
        return p;
    }

    public List<Attachment> list(Feedback feedback) {
        return repo.findByFeedbackOrderByIdAsc(feedback);
    }

    private Path dirFor(Feedback feedback) {
        return root.resolve(String.valueOf(feedback.getId()));
    }

    private static String clean(String name) {
        if (name == null || name.isBlank()) return "file";
        String base = Path.of(name).getFileName().toString();
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        return base.length() > 80 ? base.substring(base.length() - 80) : base;
    }
}
