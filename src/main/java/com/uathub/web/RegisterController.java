package com.uathub.web;

import com.uathub.domain.*;
import com.uathub.repo.FeedbackRepository;
import com.uathub.security.CurrentUser;
import com.uathub.service.CycleService;
import com.uathub.service.ExcelService;
import com.uathub.service.ProjectContext;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

@Controller
public class RegisterController {

    public record StageCount(Stage stage, long count) {}

    private static final List<Stage> STRIP = List.of(Stage.LOGGED, Stage.NEEDS_INFO, Stage.BUSINESS_REVIEW,
            Stage.DECIDED, Stage.IN_JIRA, Stage.CLOSED);

    private final ProjectContext ctx;
    private final FeedbackRepository repo;
    private final ExcelService excel;
    private final CycleService cycles;

    public RegisterController(ProjectContext ctx, FeedbackRepository repo, ExcelService excel, CycleService cycles) {
        this.ctx = ctx;
        this.repo = repo;
        this.excel = excel;
        this.cycles = cycles;
    }

    @GetMapping("/")
    public String register(@AuthenticationPrincipal CurrentUser me, HttpSession session, Model model,
                           @RequestParam(required = false) String q,
                           @RequestParam(required = false) String lob,
                           @RequestParam(required = false) String division,
                           @RequestParam(required = false) FeedbackType type,
                           @RequestParam(required = false) Stage stage,
                           @RequestParam(required = false) boolean all) {
        Project project = ctx.current(me, session);
        if (project == null) return "no-project";

        UatCycle cycle = cycles.current(project, session);
        boolean everyCycle = all || cycle == null;
        List<Feedback> items = everyCycle ? repo.findByProjectOrderByIdDesc(project)
                : repo.findByProjectAndUatCycleOrderByIdDesc(project, cycle);
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Feedback> rows = items.stream()
                .filter(f -> needle.isEmpty() || matches(f, needle))
                .filter(f -> blank(lob) || lob.equals(f.getLob()))
                .filter(f -> blank(division) || division.equals(f.getDivision()))
                .filter(f -> type == null || type == f.getType())
                .filter(f -> stage == null || stage == f.getStage())
                .toList();

        model.addAttribute("counts", STRIP.stream()
                .map(s -> new StageCount(s, items.stream().filter(f -> f.getStage() == s).count())).toList());
        model.addAttribute("rows", rows);
        model.addAttribute("types", FeedbackType.values());
        model.addAttribute("severities", Severity.values());
        model.addAttribute("q", q);
        model.addAttribute("fLob", lob);
        model.addAttribute("fDivision", division);
        model.addAttribute("fType", type);
        model.addAttribute("fStage", stage);
        model.addAttribute("allCycles", everyCycle);
        model.addAttribute("filtered", !needle.isEmpty() || !blank(lob) || !blank(division) || type != null || stage != null);
        return "register";
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@AuthenticationPrincipal CurrentUser me, HttpSession session,
                                         @RequestParam(required = false) boolean all) throws IOException {
        Project project = ctx.require(me, session);
        UatCycle cycle = all ? null : cycles.current(project, session);
        String name = (project.getName() + "-" + (cycle == null ? "all-cycles" : cycle.getName()))
                .replaceAll("[^A-Za-z0-9]+", "-") + "-" + LocalDate.now() + ".xlsx";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .contentType(MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(excel.export(project, cycle));
    }

    @PostMapping("/import")
    public String importSheet(@AuthenticationPrincipal CurrentUser me, HttpSession session,
                              @RequestParam("file") MultipartFile file, RedirectAttributes ra) throws IOException {
        Project project = ctx.require(me, session);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Choose an Excel file to import");
        UatCycle cycle = cycles.requireOpen(project, session);
        int n = excel.importSheet(project, cycle, ctx.user(me), file.getInputStream());
        ra.addFlashAttribute("ok", "Imported " + n + " item(s) into " + cycle.getName() + ". They are waiting for triage.");
        return "redirect:/";
    }

    private static boolean matches(Feedback f, String needle) {
        return f.getCode().toLowerCase(Locale.ROOT).contains(needle)
                || f.getTitle().toLowerCase(Locale.ROOT).contains(needle)
                || f.getWhere().toLowerCase(Locale.ROOT).contains(needle)
                || (f.getJiraKey() != null && f.getJiraKey().toLowerCase(Locale.ROOT).contains(needle));
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
