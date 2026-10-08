package com.uathub.web;

import com.uathub.domain.Priority;
import com.uathub.domain.Project;
import com.uathub.domain.Scenario;
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

    public ScenarioController(ProjectContext ctx, ScenarioRepository repo, ScenarioService service) {
        this.ctx = ctx;
        this.repo = repo;
        this.service = service;
    }

    @GetMapping
    public String library(@AuthenticationPrincipal CurrentUser me, HttpSession session, Model model,
                          @RequestParam(required = false) String q,
                          @RequestParam(required = false) String lob,
                          @RequestParam(required = false) Long id,
                          @RequestParam(required = false) boolean archived) {
        Project project = ctx.current(me, session);
        if (project == null) return "no-project";
        List<Scenario> all = repo.findByProjectOrderByCodeAsc(project);
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
        return "scenarios";
    }

    @GetMapping("/new")
    public String newForm(@AuthenticationPrincipal CurrentUser me, HttpSession session, Model model) {
        if (ctx.current(me, session) == null) return "no-project";
        model.addAttribute("s", null);
        model.addAttribute("priorities", Priority.values());
        return "scenario-edit";
    }

    @GetMapping("/{id}/edit")
    public String edit(@AuthenticationPrincipal CurrentUser me, HttpSession session, @PathVariable Long id, Model model) {
        model.addAttribute("s", service.load(id, ctx.require(me, session)));
        model.addAttribute("priorities", Priority.values());
        return "scenario-edit";
    }

    @PostMapping
    public String create(@AuthenticationPrincipal CurrentUser me, HttpSession session, HttpServletRequest req,
                         RedirectAttributes ra) {
        Scenario s = service.save(ctx.require(me, session), null, input(req));
        ra.addFlashAttribute("ok", "Created " + s.getCode() + ".");
        return "redirect:/scenarios?id=" + s.getId();
    }

    @PostMapping("/{id}")
    public String update(@AuthenticationPrincipal CurrentUser me, HttpSession session, @PathVariable Long id,
                         HttpServletRequest req, RedirectAttributes ra) {
        Scenario s = service.save(ctx.require(me, session), id, input(req));
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

    @PostMapping("/import")
    public String importSheet(@AuthenticationPrincipal CurrentUser me, HttpSession session,
                              @RequestParam("file") MultipartFile file, RedirectAttributes ra) throws IOException {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("Choose an Excel file to import");
        ScenarioService.ImportResult r = service.importSheet(ctx.require(me, session), file.getInputStream());
        ra.addFlashAttribute("ok", "Imported scenarios: " + r.created() + " new, " + r.updated() + " updated.");
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
        return new ScenarioService.ScenarioInput(req.getParameter("code"), req.getParameter("title"),
                req.getParameter("lob"), req.getParameter("division"), req.getParameter("module"),
                Priority.parse(req.getParameter("priority"), Priority.MEDIUM),
                req.getParameter("preconditions"), req.getParameter("testData"), steps);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
