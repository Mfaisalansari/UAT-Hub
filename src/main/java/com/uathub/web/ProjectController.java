package com.uathub.web;

import com.uathub.domain.Project;
import com.uathub.repo.ProjectRepository;
import com.uathub.notify.NotificationDispatcher;
import com.uathub.service.CycleService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequestMapping("/projects")
public class ProjectController {

    private final ProjectRepository projects;
    private final CycleService cycles;
    private final NotificationDispatcher dispatcher;

    public ProjectController(ProjectRepository projects, CycleService cycles, NotificationDispatcher dispatcher) {
        this.projects = projects;
        this.cycles = cycles;
        this.dispatcher = dispatcher;
    }

    @GetMapping
    public String page(Model model) {
        model.addAttribute("allProjects", projects.findAllByOrderByNameAsc());
        model.addAttribute("emailConfigured", dispatcher.emailConfigured());
        return "projects";
    }

    @PostMapping
    public String create(@RequestParam String name, @RequestParam(required = false) String currentCycle,
                         RedirectAttributes ra) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Add a project name");
        if (projects.findByNameIgnoreCase(name.trim()).isPresent()) {
            throw new IllegalArgumentException("A project called " + name.trim() + " already exists");
        }
        Project p = new Project();
        p.setName(name.trim());
        projects.save(p);
        cycles.findOrCreate(p, blankToNull(currentCycle));
        ra.addFlashAttribute("ok", "Created " + p.getName() + " with its first UAT cycle. Give people access from Team & access.");
        return "redirect:/projects";
    }

    @PostMapping("/{id}")
    public String update(@PathVariable Long id,
                         @RequestParam String name,
                         @RequestParam(required = false) String jiraProjectKey,
                         @RequestParam(required = false) String bugIssueType,
                         @RequestParam(required = false) String storyIssueType,
                         @RequestParam(required = false) String jiraComponents,
                         @RequestParam(required = false) String jiraLabels,
                         @RequestParam(required = false) String jiraFixVersion,
                         @RequestParam(required = false) String teamsWebhook,
                         RedirectAttributes ra) {
        Project p = projects.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Add a project name");
        p.setName(name.trim());
        p.setJiraProjectKey(jiraProjectKey == null ? null : blankToNull(jiraProjectKey.toUpperCase()));
        p.setBugIssueType(blankToNull(bugIssueType));
        p.setStoryIssueType(blankToNull(storyIssueType));
        p.setJiraComponents(blankToNull(jiraComponents));
        p.setJiraLabels(blankToNull(jiraLabels));
        p.setJiraFixVersion(blankToNull(jiraFixVersion));
        String hook = blankToNull(teamsWebhook);
        if (hook != null && !hook.startsWith("https://")) throw new IllegalArgumentException("The Teams webhook must start with https://");
        p.setTeamsWebhook(hook);
        projects.save(p);
        ra.addFlashAttribute("ok", "Saved " + p.getName() + ".");
        return "redirect:/projects";
    }

    @PostMapping("/{id}/teams-test")
    public String teamsTest(@PathVariable Long id, RedirectAttributes ra) {
        Project p = projects.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (p.getTeamsWebhook() == null) throw new IllegalArgumentException("Save a Teams webhook for " + p.getName() + " first");
        String error = dispatcher.teamsTest(p);
        if (error == null) ra.addFlashAttribute("ok", "Test message sent to Teams for " + p.getName() + ".");
        else ra.addFlashAttribute("err", "Teams didn't accept the message: " + error);
        return "redirect:/projects";
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
