package com.uathub.web;

import com.uathub.domain.*;
import com.uathub.repo.FeedbackRepository;
import com.uathub.security.CurrentUser;
import com.uathub.service.AttachmentStorage;
import com.uathub.service.FeedbackService;
import com.uathub.service.ProjectContext;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/review")
public class ReviewController {

    private final ProjectContext ctx;
    private final FeedbackRepository repo;
    private final FeedbackService service;
    private final AttachmentStorage storage;

    public ReviewController(ProjectContext ctx, FeedbackRepository repo, FeedbackService service, AttachmentStorage storage) {
        this.ctx = ctx;
        this.repo = repo;
        this.service = service;
        this.storage = storage;
    }

    @GetMapping
    public String queue(@AuthenticationPrincipal CurrentUser me, HttpSession session, Model model,
                        @RequestParam(required = false) Long id) {
        Project project = ctx.current(me, session);
        if (project == null) return "no-project";
        List<Feedback> queue = repo.findByProjectAndStageOrderByIdAsc(project, Stage.BUSINESS_REVIEW);
        Feedback item = queue.stream().filter(f -> f.getId().equals(id)).findFirst()
                .orElse(queue.isEmpty() ? null : queue.get(0));
        model.addAttribute("queue", queue);
        model.addAttribute("item", item);
        model.addAttribute("decisions", Decision.values());
        if (item != null) {
            model.addAttribute("files", storage.list(item));
            model.addAttribute("trail", service.trail(item));
        }
        return "review";
    }

    @PostMapping("/{id}/decide")
    public String decide(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id,
                         @RequestParam(required = false) Decision decision,
                         @RequestParam(required = false) String rationale,
                         @RequestParam(required = false) String targetRelease,
                         RedirectAttributes ra) {
        AppUser user = ctx.user(me);
        Feedback f = service.load(id, user);
        service.decide(f, user, decision, rationale, targetRelease);
        ra.addFlashAttribute("ok", "Recorded " + f.getCode() + " as " + decision.getLabel() + ".");
        return "redirect:/review";
    }
}
