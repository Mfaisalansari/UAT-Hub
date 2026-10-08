package com.uathub.web;

import com.uathub.domain.*;
import com.uathub.security.CurrentUser;
import com.uathub.service.AttachmentStorage;
import com.uathub.service.CycleService;
import com.uathub.service.FeedbackService;
import com.uathub.service.ProjectContext;
import com.uathub.service.TestingService;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

@Controller
public class ExecController {

    private final ProjectContext ctx;
    private final TestingService testing;
    private final FeedbackService feedback;
    private final AttachmentStorage storage;
    private final CycleService cycles;

    public ExecController(ProjectContext ctx, TestingService testing, FeedbackService feedback, AttachmentStorage storage,
                          CycleService cycles) {
        this.ctx = ctx;
        this.testing = testing;
        this.feedback = feedback;
        this.storage = storage;
        this.cycles = cycles;
    }

    @GetMapping("/my")
    public String mine(@AuthenticationPrincipal CurrentUser me, HttpSession session, Model model) {
        Project project = ctx.current(me, session);
        if (project == null) return "no-project";
        UatCycle cycle = cycles.current(project, session);
        if (cycle != null) {
            List<Execution> queue = testing.myQueue(cycle, ctx.user(me));
            model.addAttribute("queue", queue);
            model.addAttribute("totals", testing.totals(queue));
            model.addAttribute("results", queue.stream().collect(java.util.stream.Collectors.toMap(Execution::getId, x -> testing.results(x))));
            model.addAttribute("links", queue.stream().collect(java.util.stream.Collectors.toMap(Execution::getId, x -> testing.links(x))));
        }
        return "my";
    }

    @GetMapping("/exec/{id}")
    public String run(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id,
                      @RequestParam(required = false) Integer step, Model model) {
        AppUser user = ctx.user(me);
        Execution e = testing.loadExecution(id, user);
        List<ScenarioStep> steps = e.getScenario().getSteps();
        Map<Long, StepResult> results = testing.results(e);
        ScenarioStep cur = null;
        if (step != null) cur = steps.stream().filter(s -> s.getStepNo() == step).findFirst().orElse(null);
        if (cur == null) cur = steps.stream().filter(s -> !results.containsKey(s.getId())).findFirst()
                .orElse(steps.isEmpty() ? null : steps.get(steps.size() - 1));
        StepResult curResult = cur == null ? null : results.get(cur.getId());
        List<IssueLink> links = testing.links(e);
        model.addAttribute("e", e);
        model.addAttribute("steps", steps);
        model.addAttribute("results", results);
        model.addAttribute("cur", cur);
        model.addAttribute("curResult", curResult);
        model.addAttribute("evidence", curResult == null ? List.of() : storage.list(curResult));
        model.addAttribute("links", links);
        final ScenarioStep c = cur;
        model.addAttribute("curLinks", c == null ? List.of()
                : links.stream().filter(l -> l.getStepNo() != null && l.getStepNo() == c.getStepNo()).toList());
        model.addAttribute("canRecord", testing.canRecord(e, user));
        model.addAttribute("statuses", StepStatus.values());
        model.addAttribute("severities", Severity.values());
        model.addAttribute("similar", cur == null || curResult == null ? List.of()
                : feedback.similar(e.getRun().getProject(), e.getScenario().getTitle() + " " + cur.getAction()
                        + (curResult.getActual() == null ? "" : " " + curResult.getActual()), null));
        return "exec";
    }

    @PostMapping("/exec/{id}/step/{stepId}")
    public String record(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id, @PathVariable Long stepId,
                         @RequestParam(required = false) StepStatus result,
                         @RequestParam(required = false) String actual,
                         @RequestParam(value = "files", required = false) List<MultipartFile> files) {
        AppUser user = ctx.user(me);
        Execution e = testing.loadExecution(id, user);
        ScenarioStep step = testing.step(e, stepId);
        testing.record(e, step, user, result, actual, files);
        int next = step.getStepNo();
        boolean stop = result == StepStatus.FAIL || result == StepStatus.BLOCKED;
        if (!stop && next < e.getScenario().getSteps().size()) next++;
        return "redirect:/exec/" + id + "?step=" + next;
    }

    @PostMapping("/exec/{id}/step/{stepId}/raise")
    public String raise(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id, @PathVariable Long stepId,
                        @RequestParam(required = false) String title,
                        @RequestParam(required = false) Severity severity,
                        @RequestParam(value = "files", required = false) List<MultipartFile> files,
                        RedirectAttributes ra) {
        AppUser user = ctx.user(me);
        Execution e = testing.loadExecution(id, user);
        ScenarioStep step = testing.step(e, stepId);
        Feedback f = testing.raise(e, step, user, title, severity, files);
        ra.addFlashAttribute("ok", "Raised " + f.getCode() + ". It's in the register for QA triage.");
        return "redirect:/exec/" + id + "?step=" + step.getStepNo();
    }

    @PostMapping("/exec/{id}/step/{stepId}/link")
    public String link(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id, @PathVariable Long stepId,
                       @RequestParam String code, RedirectAttributes ra) {
        AppUser user = ctx.user(me);
        Execution e = testing.loadExecution(id, user);
        ScenarioStep step = testing.step(e, stepId);
        Feedback f = testing.linkExisting(e, step, user, code);
        ra.addFlashAttribute("ok", "Linked " + f.getCode() + " to this step.");
        return "redirect:/exec/" + id + "?step=" + step.getStepNo();
    }

    @PostMapping("/exec/{id}/finish")
    public String finish(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id, RedirectAttributes ra) {
        AppUser user = ctx.user(me);
        Execution e = testing.loadExecution(id, user);
        ra.addFlashAttribute("ok", testing.finish(e, user));
        return "redirect:/my";
    }
}
