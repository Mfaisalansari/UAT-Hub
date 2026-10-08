package com.uathub.web;

import com.uathub.domain.Project;
import com.uathub.domain.UatCycle;
import com.uathub.repo.FeedbackRepository;
import com.uathub.repo.TestRunRepository;
import com.uathub.security.CurrentUser;
import com.uathub.service.CycleService;
import com.uathub.service.ProjectContext;
import jakarta.servlet.http.HttpSession;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** UAT cycles of the current project: one per UAT window, e.g. October and November releases. */
@Controller
@RequestMapping("/cycles")
public class CycleController {

    private final ProjectContext ctx;
    private final CycleService cycles;
    private final TestRunRepository runs;
    private final FeedbackRepository feedback;

    public CycleController(ProjectContext ctx, CycleService cycles, TestRunRepository runs, FeedbackRepository feedback) {
        this.ctx = ctx;
        this.cycles = cycles;
        this.runs = runs;
        this.feedback = feedback;
    }

    @GetMapping
    public String page(@AuthenticationPrincipal CurrentUser me, HttpSession session, Model model) {
        Project project = ctx.current(me, session);
        if (project == null) return "no-project";
        List<UatCycle> all = cycles.cycles(project);
        Map<Long, Long> runCount = new HashMap<>();
        Map<Long, Long> feedbackCount = new HashMap<>();
        for (UatCycle c : all) {
            runCount.put(c.getId(), runs.countByUatCycle(c));
            feedbackCount.put(c.getId(), feedback.countByUatCycle(c));
        }
        model.addAttribute("allCycles", all);
        model.addAttribute("runCount", runCount);
        model.addAttribute("feedbackCount", feedbackCount);
        return "cycles";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal CurrentUser me, HttpSession session,
                         @RequestParam String name,
                         @RequestParam(required = false) String releaseName,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                         RedirectAttributes ra) {
        Project project = ctx.require(me, session);
        UatCycle c = cycles.save(project, null, name, releaseName, startDate, endDate);
        cycles.select(project, session, c.getId());
        ra.addFlashAttribute("ok", "Created " + c.getName() + " and switched to it. Start its first test run.");
        return "redirect:/runs";
    }

    @PostMapping("/{id}")
    public String update(@AuthenticationPrincipal CurrentUser me, HttpSession session, @PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(required = false) String releaseName,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
                         RedirectAttributes ra) {
        UatCycle c = cycles.save(ctx.require(me, session), id, name, releaseName, startDate, endDate);
        ra.addFlashAttribute("ok", "Saved " + c.getName() + ".");
        return "redirect:/cycles";
    }

    @PostMapping("/{id}/open")
    public String setOpen(@AuthenticationPrincipal CurrentUser me, HttpSession session, @PathVariable Long id,
                          @RequestParam boolean open, RedirectAttributes ra) {
        UatCycle c = cycles.setOpen(cycles.load(id, ctx.require(me, session)), open);
        ra.addFlashAttribute("ok", c.getName() + (open ? " reopened." : " closed. Its runs, results and feedback stay available to view."));
        return "redirect:/cycles";
    }
}
