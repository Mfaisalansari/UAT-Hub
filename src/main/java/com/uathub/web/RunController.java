package com.uathub.web;

import com.uathub.domain.*;
import com.uathub.repo.ScenarioRepository;
import com.uathub.security.CurrentUser;
import com.uathub.service.ProjectContext;
import com.uathub.service.TestingService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.*;

@Controller
@RequestMapping("/runs")
public class RunController {

    private final ProjectContext ctx;
    private final TestingService testing;
    private final ScenarioRepository scenarios;

    public RunController(ProjectContext ctx, TestingService testing, ScenarioRepository scenarios) {
        this.ctx = ctx;
        this.testing = testing;
        this.scenarios = scenarios;
    }

    @GetMapping
    public String board(@AuthenticationPrincipal CurrentUser me, HttpSession session, Model model,
                        @RequestParam(required = false) Long id) {
        Project project = ctx.current(me, session);
        if (project == null) return "no-project";
        TestRun run = id != null ? testing.loadRun(id, project) : testing.currentRun(project).orElse(null);
        model.addAttribute("run", run);
        model.addAttribute("allRuns", testing.runs(project));
        if (run != null) {
            List<TestingService.LobRow> lobRows = testing.lobRows(run);
            TestingService.Totals t = testing.totals(testing.executionsByScenario(run).values());
            model.addAttribute("totals", t);
            model.addAttribute("lobRows", lobRows);
            model.addAttribute("testers", testing.testerRows(run));
            model.addAttribute("issues", testing.issueRows(run));
            model.addAttribute("fixCandidates", testing.fixCandidates(run));
        }
        return "runs";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal CurrentUser me, HttpSession session,
                         @RequestParam String name, @RequestParam(required = false) String build,
                         RedirectAttributes ra) {
        TestRun run = testing.createRun(ctx.require(me, session), ctx.user(me), name, build);
        ra.addFlashAttribute("ok", "Started " + run.getLabel() + ". Now assign scenarios to people.");
        return "redirect:/runs/" + run.getId() + "/assign";
    }

    @GetMapping("/{id}/assign")
    public String assignForm(@AuthenticationPrincipal CurrentUser me, HttpSession session, @PathVariable Long id,
                             @RequestParam(required = false) String lob, Model model) {
        Project project = ctx.require(me, session);
        TestRun run = testing.loadRun(id, project);
        List<Scenario> list = scenarios.findByProjectAndActiveTrueOrderByCodeAsc(project).stream()
                .filter(s -> lob == null || lob.isBlank() || lob.equals(s.getLob()))
                .toList();
        model.addAttribute("run", run);
        model.addAttribute("rows", list);
        model.addAttribute("current", testing.executionsByScenario(run));
        model.addAttribute("people", testing.assignable(project));
        model.addAttribute("fLob", lob);
        return "assign";
    }

    /**
     * Each row posts a_{scenarioId} = userId (blank = not in the run). The bulk bar posts
     * bulkUser plus sel = scenario ids, which overrides the rows it covers.
     */
    @PostMapping("/{id}/assign")
    public String assign(@AuthenticationPrincipal CurrentUser me, HttpSession session, @PathVariable Long id,
                         HttpServletRequest req, RedirectAttributes ra) {
        TestRun run = testing.loadRun(id, ctx.require(me, session));
        Map<Long, Long> wanted = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> p : req.getParameterMap().entrySet()) {
            if (!p.getKey().startsWith("a_")) continue;
            Long scenarioId = parse(p.getKey().substring(2));
            if (scenarioId != null) wanted.put(scenarioId, parse(p.getValue()[0]));
        }
        String bulk = req.getParameter("bulkUser");
        String[] sel = req.getParameterValues("sel");
        if (bulk != null && !bulk.isBlank() && sel != null && req.getParameter("applyBulk") != null) {
            Long userId = "none".equals(bulk) ? null : parse(bulk);
            for (String s : sel) {
                Long sid = parse(s);
                if (sid != null) wanted.put(sid, userId);
            }
        }
        int n = testing.assign(run, wanted);
        ra.addFlashAttribute("ok", n == 0 ? "No changes." : "Updated " + n + " assignment(s).");
        String lob = req.getParameter("lob");
        return "redirect:/runs/" + id + "/assign" + (lob == null || lob.isBlank() ? "" : "?lob=" + java.net.URLEncoder.encode(lob, java.nio.charset.StandardCharsets.UTF_8));
    }

    @PostMapping("/{id}/build")
    public String deployBuild(@AuthenticationPrincipal CurrentUser me, HttpSession session, @PathVariable Long id,
                              @RequestParam String build,
                              @RequestParam(value = "fixed", required = false) List<Long> fixed,
                              RedirectAttributes ra) {
        TestRun run = testing.loadRun(id, ctx.require(me, session));
        int n = testing.deployBuild(run, ctx.user(me), build, fixed);
        ra.addFlashAttribute("ok", "Now testing " + build.trim() + "."
                + (n > 0 ? " " + n + " scenario(s) sent back for re-test." : ""));
        return "redirect:/runs?id=" + id;
    }

    @PostMapping("/{id}/signoff")
    public String signOff(@AuthenticationPrincipal CurrentUser me, HttpSession session, @PathVariable Long id,
                          @RequestParam String lob, @RequestParam(required = false) String note,
                          RedirectAttributes ra) {
        TestRun run = testing.loadRun(id, ctx.require(me, session));
        LobSignOff s = testing.signOff(run, ctx.user(me), lob, note);
        ra.addFlashAttribute("ok", "Signed off " + s.getLob() + "."
                + (s.getAcceptedIssues() == null ? "" : " Accepted open issues: " + s.getAcceptedIssues() + "."));
        return "redirect:/runs?id=" + id;
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal CurrentUser me, HttpSession session,
                                         @PathVariable Long id) throws IOException {
        TestRun run = testing.loadRun(id, ctx.require(me, session));
        String name = (run.getLabel()).replaceAll("[^A-Za-z0-9]+", "-") + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(testing.export(run));
    }

    private static Long parse(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
