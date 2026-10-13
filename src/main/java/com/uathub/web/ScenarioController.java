package com.uathub.web;

import com.uathub.domain.Priority;
import com.uathub.domain.Project;
import com.uathub.domain.Scenario;
import com.uathub.domain.UatCycle;
import com.uathub.repo.ScenarioRepository;
import com.uathub.security.CurrentUser;
import com.uathub.service.ProjectContext;
import com.uathub.service.ScenarioService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Controller
@RequestMapping("/scenarios")
public class ScenarioController {

    private final ProjectContext ctx;
    private final ScenarioRepository repo;
    private final ScenarioService service;
    private final com.uathub.config.UatHubProperties props;
    private final com.uathub.service.CycleService cycles;

    public ScenarioController(ProjectContext ctx, ScenarioRepository repo, ScenarioService service,
                              com.uathub.config.UatHubProperties props, com.uathub.service.CycleService cycles) {
        this.cycles = cycles;
        this.ctx = ctx;
        this.repo = repo;
        this.service = service;
        this.props = props;
    }

    @GetMapping
    public String library(@AuthenticationPrincipal CurrentUser me, HttpSession session, Model model,
                          @RequestParam(required = false) String q,
                          @RequestParam(required = false) String lob,
                          @RequestParam(required = false) Long id,
                          @RequestParam(required = false) boolean archived) {
        Project project = ctx.current(me, session);
        if (project == null) return "no-project";
        UatCycle cycle = cycles.current(project, session);
        List<Scenario> all = service.library(cycle);
        String needle = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
        List<Scenario> rows = all.stream()
                .filter(s -> s.isActive() != archived)
                .filter(s -> lob == null || lob.isBlank() || lob.equals(s.getLob()))
                .filter(s -> needle.isEmpty() || s.getCode().toLowerCase(Locale.ROOT).contains(needle)
                        || s.getTitle().toLowerCase(Locale.ROOT).contains(needle)
                        || (s.getModule() != null && s.getModule().toLowerCase(Locale.ROOT).contains(needle)))
                .toList();
        Scenario sel = rows.stream().filter(s -> s.getId().equals(id)).findFirst().orElse(rows.isEmpty() ? null : rows.get(0));
        model.addAttribute("rows", rows);
        model.addAttribute("sel", sel);
        model.addAttribute("q", q);
        model.addAttribute("fLob", lob);
        model.addAttribute("archived", archived);
        model.addAttribute("activeCount", all.stream().filter(Scenario::isActive).count());
        model.addAttribute("archivedCount", all.stream().filter(s -> !s.isActive()).count());
        model.addAttribute("caseCount", all.stream().filter(Scenario::isActive).mapToInt(Scenario::getCaseCount).sum());
        model.addAttribute("otherCycles", cycle == null ? List.of() : cycles.cycles(project).stream()
                .filter(c -> !c.getId().equals(cycle.getId())).toList());
        return "scenarios";
    }

    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal CurrentUser me, HttpSession session, Model model) {
        Project project = ctx.current(me, session);
        if (project == null) return "no-project";
        cycles.requireOpen(project, session);
        model.addAttribute("s", null);
        model.addAttribute("priorities", Priority.values());
        model.addAttribute("channels", props.channelsSafe());
        return "scenario-edit";
    }

    @GetMapping("/{id}/edit")
    public String edit(@AuthenticationPrincipal CurrentUser me, HttpSession session, @PathVariable Long id, Model model) {
        model.addAttribute("s", service.load(id, ctx.require(me, session)));
        model.addAttribute("priorities", Priority.values());
        model.addAttribute("channels", props.channelsSafe());
        return "scenario-edit";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal CurrentUser me, HttpSession session, HttpServletRequest req,
                         RedirectAttributes ra) {
        Project project = ctx.require(me, session);
        Scenario s = service.save(project, cycles.requireOpen(project, session), null, input(req));
        ra.addFlashAttribute("ok", "Created " + s.getCode() + " in " + s.getUatCycle().getName() + ".");
        return "redirect:/scenarios?id=" + s.getId();
    }

    @PostMapping("/{id}")
    public String update(@AuthenticationPrincipal CurrentUser me, HttpSession session, @PathVariable Long id,
                         HttpServletRequest req, RedirectAttributes ra) {
        Scenario s = service.save(ctx.require(me, session), null, id, input(req));
        ra.addFlashAttribute("ok", "Saved " + s.getCode() + ".");
        return "redirect:/scenarios?id=" + s.getId();
    }

    @PostMapping("/{id}/archive")
    public String archive(@AuthenticationPrincipal CurrentUser me, HttpSession session, @PathVariable Long id,
                          @RequestParam boolean active, RedirectAttributes ra) {
        Scenario s = service.load(id, ctx.require(me, session));
        service.setActive(s, active);
        ra.addFlashAttribute("ok", s.getCode() + (active ? " restored." : " archived. It stays in past runs but can't be assigned."));
        return "redirect:/scenarios" + (active ? "?id=" + id : "");
    }

    @GetMapping("/template")
    public org.springframework.http.ResponseEntity<byte[]> template() throws IOException {
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment().filename("UAT-scenarios-template.xlsx").build().toString())
                .contentType(org.springframework.http.MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(service.template(props.lobs(), props.divisions(), props.channelsSafe()));
    }

    @PostMapping("/import")
    public String importSheet(@AuthenticationPrincipal CurrentUser me, HttpSession session,
                              @RequestParam("file") MultipartFile file, RedirectAttributes ra) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Choose an Excel file to import");
        Project project = ctx.require(me, session);
        UatCycle cycle = cycles.requireOpen(project, session);
        ScenarioService.ImportResult r = service.importSheet(project, cycle, file.getInputStream());
        ra.addFlashAttribute("ok", "Imported into " + cycle.getName() + ": " + r.created() + " new, " + r.updated() + " updated"
                + (r.dataSets() > 0 ? ", " + r.dataSets() + " test data set(s)." : "."));
        return "redirect:/scenarios";
    }

    /** Copies scenarios (with steps and data sets) from another cycle of the project into the current one. */
    @PostMapping("/copy")
    public String copy(@AuthenticationPrincipal CurrentUser me, HttpSession session,
                       @RequestParam Long from, @RequestParam(required = false) String lob, RedirectAttributes ra) {
        Project project = ctx.require(me, session);
        UatCycle target = cycles.requireOpen(project, session);
        UatCycle source = cycles.load(from, project);
        ScenarioService.CopyResult r = service.copyFrom(target, source, lob);
        ra.addFlashAttribute("ok", "Copied " + r.copied() + " scenario(s) from " + source.getName() + " into " + target.getName() + "."
                + (r.skipped() > 0 ? " Skipped " + r.skipped() + " whose ID is already in this cycle." : ""));
        return "redirect:/scenarios";
    }

    /** Steps arrive as repeated action/expected fields; read them raw so commas in the text are kept. */
    private static ScenarioService.ScenarioInput input(HttpServletRequest req) {
        String[] actions = req.getParameterValues("action");
        String[] expected = req.getParameterValues("expected");
        List<String[]> steps = new ArrayList<>();
        if (actions != null) {
            for (int i = 0; i < actions.length; i++) {
                String a = actions[i] == null ? "" : actions[i].trim();
                String e = expected != null && i < expected.length && expected[i] != null ? expected[i].trim() : "";
                if (a.isEmpty()) continue;
                steps.add(new String[]{cut(a, 1000), e.isEmpty() ? null : cut(e, 1000)});
            }
        }
        // Data sets arrive as parallel ds_name / ds_channel / ds_persona / ds_data fields; rows without a name are ignored.
        String[] names = req.getParameterValues("ds_name");
        String[] channels = req.getParameterValues("ds_channel");
        String[] personas = req.getParameterValues("ds_persona");
        String[] data = req.getParameterValues("ds_data");
        List<String[]> sets = new ArrayList<>();
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                String n = names[i] == null ? "" : names[i].trim();
                if (n.isEmpty()) continue;
                sets.add(new String[]{cut(n, 120), blankToNull(at(channels, i), 255), blankToNull(at(personas, i), 255),
                        blankToNull(at(data, i), 2000)});
            }
        }
        return new ScenarioService.ScenarioInput(req.getParameter("code"), req.getParameter("title"),
                req.getParameter("lob"), req.getParameter("division"), req.getParameter("module"),
                Priority.parse(req.getParameter("priority"), Priority.MEDIUM),
                req.getParameter("preconditions"), req.getParameter("testData"), steps, sets);
    }

    private static String at(String[] a, int i) {
        return a != null && i < a.length ? a[i] : null;
    }

    private static String blankToNull(String s, int max) {
        if (s == null || s.isBlank()) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
