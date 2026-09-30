package com.uathub.web;

import com.uathub.domain.Feedback;
import com.uathub.domain.Project;
import com.uathub.domain.Stage;
import com.uathub.repo.FeedbackRepository;
import com.uathub.security.CurrentUser;
import com.uathub.service.JiraService;
import com.uathub.service.ProjectContext;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;

@Controller
@RequestMapping("/jira")
public class JiraController {

    private final ProjectContext ctx;
    private final FeedbackRepository repo;
    private final JiraService jira;

    public JiraController(ProjectContext ctx, FeedbackRepository repo, JiraService jira) {
        this.ctx = ctx;
        this.repo = repo;
        this.jira = jira;
    }

    @GetMapping
    public String page(@AuthenticationPrincipal CurrentUser me, HttpSession session, Model model,
                       @RequestParam(required = false) Long preview) {
        Project project = ctx.current(me, session);
        if (project == null) return "no-project";
        List<Feedback> ready = repo.findByProjectAndStageOrderByIdAsc(project, Stage.DECIDED);
        Feedback p = ready.stream().filter(f -> f.getId().equals(preview)).findFirst()
                .orElse(ready.isEmpty() ? null : ready.get(0));
        model.addAttribute("ready", ready);
        model.addAttribute("recent", repo.findTop10ByProjectAndStageOrderByUpdatedAtDesc(project, Stage.IN_JIRA));
        model.addAttribute("configured", jira.configured());
        model.addAttribute("previewItem", p);
        model.addAttribute("preview", p == null ? null : jira.preview(p));
        return "jira";
    }

    @PostMapping("/push")
    public String push(@AuthenticationPrincipal CurrentUser me,
                       @RequestParam(value = "ids", required = false) List<Long> ids,
                       RedirectAttributes ra) {
        if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("Select at least one item to push");
        List<JiraService.Result> results = jira.push(ids, ctx.user(me));
        long ok = results.stream().filter(JiraService.Result::ok).count();
        ra.addFlashAttribute("results", results);
        if (ok == results.size()) ra.addFlashAttribute("ok", "Created " + ok + " Jira issue(s).");
        else ra.addFlashAttribute("err", "Created " + ok + " of " + results.size() + ". See the details below.");
        return "redirect:/jira";
    }
}
