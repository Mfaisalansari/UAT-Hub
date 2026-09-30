package com.uathub.web;

import com.uathub.config.UatHubProperties;
import com.uathub.domain.AppUser;
import com.uathub.domain.Role;
import com.uathub.repo.ProjectRepository;
import com.uathub.security.CurrentUser;
import com.uathub.service.SettingsService;
import com.uathub.service.TeamService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Controller
@RequestMapping("/team")
public class TeamController {

    private final TeamService team;
    private final ProjectRepository projects;
    private final SettingsService settings;
    private final UatHubProperties props;

    public TeamController(TeamService team, ProjectRepository projects, SettingsService settings, UatHubProperties props) {
        this.team = team;
        this.projects = projects;
        this.settings = settings;
        this.props = props;
    }

    @GetMapping
    public String page(Model model) {
        List<AppUser> people = team.everyone();
        Map<Long, String> links = new LinkedHashMap<>();
        for (AppUser u : people) links.put(u.getId(), props.linkFor(u.getAccessToken()));
        model.addAttribute("people", people);
        model.addAttribute("links", links);
        model.addAttribute("roles", Role.values());
        model.addAttribute("allProjects", projects.findAllByOrderByNameAsc());
        model.addAttribute("requirePin", settings.requirePin());
        return "team";
    }

    @PostMapping
    public String add(@RequestParam String name, @RequestParam(required = false) String email,
                      @RequestParam Role role, @RequestParam(required = false) List<Long> projectIds,
                      RedirectAttributes ra) {
        AppUser u = team.add(name, email, role, projectIds);
        ra.addFlashAttribute("ok", "Added " + u.getName() + ". Copy their link and send it to them.");
        ra.addFlashAttribute("highlight", u.getId());
        return "redirect:/team";
    }

    @PostMapping("/{id}/update")
    public String update(@PathVariable Long id, @RequestParam Role role,
                         @RequestParam(required = false) List<Long> projectIds, RedirectAttributes ra) {
        AppUser u = team.update(id, role, projectIds);
        ra.addFlashAttribute("ok", "Updated " + u.getName() + ".");
        return "redirect:/team";
    }

    @PostMapping("/{id}/regenerate")
    public String regenerate(@PathVariable Long id, RedirectAttributes ra) {
        AppUser u = team.regenerate(id);
        ra.addFlashAttribute("ok", "New link for " + u.getName() + ". The old one no longer works.");
        ra.addFlashAttribute("highlight", u.getId());
        return "redirect:/team";
    }

    @PostMapping("/{id}/revoke")
    public String revoke(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id, RedirectAttributes ra) {
        AppUser u = team.setActive(id, false, me.id());
        ra.addFlashAttribute("ok", u.getName() + " can no longer open UAT Hub.");
        return "redirect:/team";
    }

    @PostMapping("/{id}/reactivate")
    public String reactivate(@PathVariable Long id, RedirectAttributes ra) {
        AppUser u = team.setActive(id, true, null);
        ra.addFlashAttribute("ok", u.getName() + " is active again with a new link.");
        ra.addFlashAttribute("highlight", u.getId());
        return "redirect:/team";
    }

    @PostMapping("/{id}/reset-pin")
    public String resetPin(@PathVariable Long id, RedirectAttributes ra) {
        AppUser u = team.resetPin(id);
        ra.addFlashAttribute("ok", u.getName() + " will choose a new PIN next time.");
        return "redirect:/team";
    }

    @PostMapping("/pin")
    public String pin(@RequestParam(defaultValue = "false") boolean on, RedirectAttributes ra) {
        settings.setRequirePin(on);
        ra.addFlashAttribute("ok", on ? "New browsers will be asked for a PIN." : "PIN check turned off.");
        return "redirect:/team";
    }
}
