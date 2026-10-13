package com.uathub.web;

import com.uathub.domain.*;
import com.uathub.repo.ScenarioRepository;
import com.uathub.security.CurrentUser;
import com.uathub.service.CycleService;
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
    private final CycleService cycles;
    private final com.uathub.service.ReportService reports;
    private final com.uathub.service.ScenarioService scenarioService;

    public RunController(ProjectContext ctx, TestingService testing, ScenarioRepository scenarios, CycleService cycles,
                         com.uathub.service.ReportService reports, com.uathub.service.ScenarioService scenarioService) {
        this.reports = reports;
        this.scenarioService = scenarioService;
        this.ctx = ctx;
        this.testing = testing;
        this.scenarios = scenarios;
        this.cycles = cycles;
    }

    /** Cycle overview: its runs, each scenario's latest result by LOB, sign-off and issues. */
    @GetMapping
    public String cycleBoard(@AuthenticationPrincipal CurrentUser me, HttpSession session, Model model) {
        Project project = ctx.current(me, session);
        if (project == null) return "no-project";
        UatCycle cycle = cycles.current(project, session);
        if (cycle != null) {
            model.addAttribute("runCards", testing.runCards(cycle));
            model.addAttribute("totals", testing.totals(testing.latestPerCase(cycle)));
            model.addAttribute("lobRows", testing.lobRows(cycle));
            model.addAttribute("issues", testing.issueRows(cycle));
        }
        return "cycle";
    }

    /** One run: progress, testers, issues raised in it, new builds. */
    @GetMapping("/{id}")
    public String runBoard(@AuthenticationPrincipal CurrentUser me, HttpSession session, @PathVariable Long id, Model model) {
        Project project = ctx.require(me, session);
        TestRun run = testing.loadRun(id, project);
        if (run.getUatCycle() != null && (cycles.current(project, session) == null
                || !run.getUatCycle().getId().equals(cycles.current(project, session).getId()))) {
            cycles.select(project, session, run.getUatCycle().getId());
            return "redirect:/runs/" + id;
        }
        model.addAttribute("run", run);
        model.addAttribute("totals", testing.totals(testing.executions(run)));
        model.addAttribute("testers", testing.testerRows(run));
        model.addAttribute("issues", testing.issueRows(run));
        model.addAttribute("fixCandidates", testing.fixCandidates(run));
        return "run";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal CurrentUser me, HttpSession session,
                         @RequestParam String name, @RequestParam(required = false) String build,
                         @RequestParam(required = false) Long copyFrom,
                         @RequestParam(required = false, defaultValue = "none") String copyMode,
                         RedirectAttributes ra) {
        UatCycle cycle = cycles.requireOpen(ctx.require(me, session), session);
        TestRun run = testing.createRun(cycle, ctx.user(me), name, build, copyFrom, copyMode);
        ra.addFlashAttribute("ok", "Started " + run.getLabel() + " in " + cycle.getName() + ". Check the assignments.");
        return "redirect:/runs/" + run.getId() + "/assign";
    }

    @PostMapping("/{id}/open")
    public String setOpen(@AuthenticationPrincipal CurrentUser me, HttpSession session, @PathVariable Long id,
                          @RequestParam boolean open, RedirectAttributes ra) {
        TestRun run = testing.loadRun(id, ctx.require(me, session));
        testing.setRunOpen(run, open);
        ra.addFlashAttribute("ok", run.getName() + (open ? " reopened." : " closed. Its results stay in the cycle."));
        return "redirect:/runs/" + id;
    }

    @GetMapping("/{id}/assign")
    public String assignForm(@AuthenticationPrincipal CurrentUser me, HttpSession session, @PathVariable Long id,
                             @RequestParam(required = false) String lob,
                             @RequestParam(required = false) String channel, Model model) {
        Project project = ctx.require(me, session);
        TestRun run = testing.loadRun(id, project);
        List<Scenario> list = scenarioService.assignable(run.getUatCycle()).stream()
                .filter(s -> lob == null || lob.isBlank() || lob.equals(s.getLob()))
                .toList();
        List<TestingService.CaseRow> rows = testing.caseRows(run, list).stream()
                .filter(r -> channel == null || channel.isBlank()
                        || (r.dataSet() != null && channel.equals(r.dataSet().getChannel())))
                .toList();
        model.addAttribute("run", run);
        model.addAttribute("rows", rows);
        model.addAttribute("channels", list.stream().flatMap(s -> s.getDataSets().stream())
                .map(d -> d.getChannel()).filter(c -> c != null && !c.isBlank()).distinct().sorted().toList());
        model.addAttribute("people", testing.assignable(project));
        model.addAttribute("fLob", lob);
        model.addAttribute("fChannel", channel);
        return "assign";
    }

    /**
     * Each row posts a_{scenarioId}_{dataSetId} = userId (blank = not in the run; dataSetId 0 when the
     * scenario has no data sets). The bulk bar posts bulkUser plus sel = "{scenarioId}_{dataSetId}" values.
     */
    @PostMapping("/{id}/assign")
    public String assign(@AuthenticationPrincipal CurrentUser me, HttpSession session, @PathVariable Long id,
                         HttpServletRequest req, RedirectAttributes ra) {
        TestRun run = testing.loadRun(id, ctx.require(me, session));
        Map<TestingService.CaseKey, Long> wanted = new LinkedHashMap<>();
        for (Map.Entry<String, String[]> p : req.getParameterMap().entrySet()) {
            if (!p.getKey().startsWith("a_")) continue;
            TestingService.CaseKey key = caseKey(p.getKey().substring(2));
            if (key != null) wanted.put(key, parse(p.getValue()[0]));
        }
        String bulk = req.getParameter("bulkUser");
        String[] sel = req.getParameterValues("sel");
        if (bulk != null && !bulk.isBlank() && sel != null && req.getParameter("applyBulk") != null) {
            Long userId = "none".equals(bulk) ? null : parse(bulk);
            for (String s : sel) {
                TestingService.CaseKey key = caseKey(s);
                if (key != null) wanted.put(key, userId);
            }
        }
        int n = testing.assign(run, wanted, ctx.user(me));
        ra.addFlashAttribute("ok", n == 0 ? "No changes." : "Updated " + n + " assignment(s).");
        StringBuilder q = new StringBuilder();
        for (String f : List.of("lob", "channel")) {
            String v = req.getParameter(f);
            if (v != null && !v.isBlank()) q.append(q.isEmpty() ? "?" : "&").append(f).append("=")
                    .append(java.net.URLEncoder.encode(v, java.nio.charset.StandardCharsets.UTF_8));
        }
        return "redirect:/runs/" + id + "/assign" + q;
    }

    private static TestingService.CaseKey caseKey(String s) {
        String[] parts = s.split("_");
        if (parts.length != 2) return null;
        Long a = parse(parts[0]), b = parse(parts[1]);
        return a == null || b == null ? null : new TestingService.CaseKey(a, b);
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
        return "redirect:/runs/" + id;
    }

    /** Business sign-off of one LOB for the current UAT cycle. */
    @PostMapping("/signoff")
    public String signOff(@AuthenticationPrincipal CurrentUser me, HttpSession session,
                          @RequestParam String lob, @RequestParam(required = false) String note,
                          RedirectAttributes ra) {
        UatCycle cycle = cycles.require(ctx.require(me, session), session);
        CycleSignOff s = testing.signOff(cycle, ctx.user(me), lob, note);
        ra.addFlashAttribute("ok", "Signed off " + s.getLob() + " for " + cycle.getName() + "."
                + (s.getAcceptedIssues() == null ? "" : " Accepted open issues: " + s.getAcceptedIssues() + "."));
        return "redirect:/runs";
    }

    /** Printable UAT exit report for the current cycle (Print → Save as PDF in the browser). */
    @GetMapping("/report")
    public String report(@AuthenticationPrincipal CurrentUser me, HttpSession session, Model model) {
        Project project = ctx.current(me, session);
        if (project == null) return "no-project";
        UatCycle cycle = cycles.current(project, session);
        if (cycle == null) return "redirect:/runs";
        model.addAttribute("r", reports.build(cycle, ctx.user(me)));
        return "report";
    }

    @GetMapping("/report.xlsx")
    public ResponseEntity<byte[]> reportExcel(@AuthenticationPrincipal CurrentUser me, HttpSession session) throws IOException {
        UatCycle cycle = cycles.require(ctx.require(me, session), session);
        return xlsx("UAT exit report " + cycle.getName(), reports.excel(reports.build(cycle, ctx.user(me))));
    }

    @GetMapping("/cycle-export")
    public ResponseEntity<byte[]> exportCycle(@AuthenticationPrincipal CurrentUser me, HttpSession session) throws IOException {
        UatCycle cycle = cycles.require(ctx.require(me, session), session);
        return xlsx(cycle.getName(), testing.export(cycle));
    }

    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal CurrentUser me, HttpSession session,
                                         @PathVariable Long id) throws IOException {
        TestRun run = testing.loadRun(id, ctx.require(me, session));
        String prefix = run.getUatCycle() == null ? "" : run.getUatCycle().getName() + " ";
        return xlsx(prefix + run.getLabel(), testing.export(run));
    }

    private static ResponseEntity<byte[]> xlsx(String title, byte[] body) {
        String name = title.replaceAll("[^A-Za-z0-9]+", "-") + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
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
