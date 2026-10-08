package com.uathub.web;

import com.uathub.domain.*;
import com.uathub.repo.AttachmentRepository;
import com.uathub.security.CurrentUser;
import com.uathub.service.AttachmentStorage;
import com.uathub.service.FeedbackService;
import com.uathub.service.ProjectContext;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

@Controller
public class FeedbackController {

    private static final String LAST_LOB = "lastLob";
    private static final String LAST_DIVISION = "lastDivision";

    private final ProjectContext ctx;
    private final FeedbackService service;
    private final AttachmentStorage storage;
    private final AttachmentRepository attachments;
    private final com.uathub.service.TestingService testing;
    private final com.uathub.service.CycleService cycles;
    private final com.uathub.service.CommentService comments;

    public FeedbackController(ProjectContext ctx, FeedbackService service, AttachmentStorage storage,
                              AttachmentRepository attachments, com.uathub.service.TestingService testing,
                              com.uathub.service.CycleService cycles, com.uathub.service.CommentService comments) {
        this.cycles = cycles;
        this.comments = comments;
        this.ctx = ctx;
        this.service = service;
        this.storage = storage;
        this.attachments = attachments;
        this.testing = testing;
    }

    @GetMapping("/feedback/new")
    public String newForm(@AuthenticationPrincipal CurrentUser me, HttpSession session, Model model) {
        if (ctx.current(me, session) == null) return "no-project";
        model.addAttribute("types", FeedbackType.values());
        model.addAttribute("severities", Severity.values());
        model.addAttribute("lastLob", session.getAttribute(LAST_LOB));
        model.addAttribute("lastDivision", session.getAttribute(LAST_DIVISION));
        return "log";
    }

    @PostMapping("/feedback")
    public String create(@AuthenticationPrincipal CurrentUser me, HttpSession session, FeedbackForm form,
                         @RequestParam(value = "files", required = false) List<MultipartFile> files,
                         @RequestParam(value = "next", required = false) String next,
                         RedirectAttributes ra) {
        Project project = ctx.require(me, session);
        Feedback f = service.create(project, cycles.requireOpen(project, session), ctx.user(me), form, files);
        // Carry LOB and division over to the next entry.
        session.setAttribute(LAST_LOB, form.lob());
        session.setAttribute(LAST_DIVISION, form.division());
        ra.addFlashAttribute("ok", "Logged " + f.getCode() + ".");
        return next != null ? "redirect:/feedback/new" : "redirect:/feedback/" + f.getId();
    }

    @GetMapping("/feedback/{id}")
    public String detail(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id, Model model) {
        AppUser user = ctx.user(me);
        Feedback f = service.load(id, user);
        model.addAttribute("f", f);
        model.addAttribute("files", storage.list(f));
        model.addAttribute("trail", service.trail(f));
        model.addAttribute("canEdit", service.canEdit(f, user));
        model.addAttribute("testLinks", testing.links(f));
        model.addAttribute("comments", comments.thread(f));
        model.addAttribute("mentionable", comments.mentionable(f));
        model.addAttribute("types", FeedbackType.values());
        model.addAttribute("severities", Severity.values());
        return "detail";
    }

    @PostMapping("/feedback/{id}/edit")
    public String edit(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id, FeedbackForm form,
                       @RequestParam(value = "files", required = false) List<MultipartFile> files,
                       @RequestParam(value = "resubmit", required = false) String resubmit,
                       RedirectAttributes ra) {
        AppUser user = ctx.user(me);
        Feedback f = service.load(id, user);
        service.update(f, user, form, files, resubmit != null);
        ra.addFlashAttribute("ok", resubmit != null ? "Updated and sent back for triage." : "Changes saved.");
        return "redirect:/feedback/" + id;
    }

    @PostMapping("/feedback/{id}/triage")
    public String triage(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id,
                         @RequestParam String action,
                         @RequestParam(required = false) FeedbackType type,
                         @RequestParam(required = false) Severity severity,
                         @RequestParam(required = false) String qaNote,
                         @RequestParam(required = false) String duplicateOf,
                         RedirectAttributes ra) {
        AppUser user = ctx.user(me);
        Feedback f = service.load(id, user);
        service.triage(f, user, action, type, severity, qaNote, duplicateOf);
        ra.addFlashAttribute("ok", switch (action) {
            case "send" -> f.getCode() + " sent to business review.";
            case "info" -> f.getCode() + " sent back to the tester for more information.";
            default -> f.getCode() + " closed as a duplicate.";
        });
        return "redirect:/feedback/" + id;
    }

    @PostMapping("/feedback/bulk")
    public String bulk(@AuthenticationPrincipal CurrentUser me, HttpSession session,
                       @RequestParam(value = "ids", required = false) List<Long> ids,
                       @RequestParam(defaultValue = "") String action,
                       @RequestParam(required = false) FeedbackType type,
                       @RequestParam(required = false) Severity severity,
                       @RequestParam(required = false) String note,
                       jakarta.servlet.http.HttpServletRequest req, RedirectAttributes ra) {
        FeedbackService.BulkResult r = service.bulk(ctx.require(me, session), ctx.user(me), ids, action, type, severity, note);
        String verb = switch (action) {
            case "send" -> "Sent " + r.done() + " item(s) to business review";
            case "info" -> "Asked for more information on " + r.done() + " item(s)";
            case "type" -> "Changed the type of " + r.done() + " item(s)";
            default -> "Changed the severity of " + r.done() + " item(s)";
        };
        ra.addFlashAttribute("ok", verb + "." + (r.skipped() == 0 ? ""
                : " Skipped " + r.skipped() + " that were already past that step or unchanged."));
        return "redirect:" + GlobalModel.sameSitePath(req.getHeader("Referer"));
    }

    @PostMapping("/feedback/{id}/comments")
    public String comment(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id, @RequestParam String text,
                          @RequestParam(required = false) String back, RedirectAttributes ra) {
        AppUser user = ctx.user(me);
        Feedback f = service.load(id, user);
        comments.add(f, user, text);
        ra.addFlashAttribute("ok", "Comment added.");
        return "review".equals(back) ? "redirect:/review?id=" + id + "#discussion" : "redirect:/feedback/" + id + "#discussion";
    }

    @GetMapping("/attachments/{id}")
    public ResponseEntity<Resource> attachment(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id) {
        Attachment a = attachments.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (!ctx.user(me).canAccess(a.getProject())) throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        MediaType type;
        try {
            type = MediaType.parseMediaType(a.getContentType());
        } catch (Exception e) {
            type = MediaType.APPLICATION_OCTET_STREAM;
        }
        ContentDisposition cd = (a.isImage() ? ContentDisposition.inline() : ContentDisposition.attachment())
                .filename(a.getFileName()).build();
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION, cd.toString())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(storage.pathOf(a)));
    }

    /** Called while typing a title on the log form. */
    @GetMapping("/api/similar")
    @ResponseBody
    public List<Map<String, Object>> similar(@AuthenticationPrincipal CurrentUser me, HttpSession session,
                                             @RequestParam String title,
                                             @RequestParam(required = false) Long exclude) {
        Project project = ctx.require(me, session);
        return service.similar(project, title, exclude).stream()
                .map(f -> Map.<String, Object>of("id", f.getId(), "code", f.getCode(),
                        "title", f.getTitle(), "stage", f.getStage().getLabel()))
                .toList();
    }
}
