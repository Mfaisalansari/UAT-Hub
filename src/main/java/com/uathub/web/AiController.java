package com.uathub.web;

import com.uathub.ai.AiClient;
import com.uathub.ai.AiService;
import com.uathub.domain.AppUser;
import com.uathub.domain.Feedback;
import com.uathub.security.CurrentUser;
import com.uathub.service.FeedbackService;
import com.uathub.service.ProjectContext;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.List;
import java.util.Map;

@Controller
public class AiController {

    private final AiService ai;
    private final ProjectContext ctx;
    private final FeedbackService feedback;

    public AiController(AiService ai, ProjectContext ctx, FeedbackService feedback) {
        this.ai = ai;
        this.ctx = ctx;
        this.feedback = feedback;
    }

    /** Called from the Log feedback form: suggests a type and severity. */
    @PostMapping("/api/ai/classify")
    @ResponseBody
    public ResponseEntity<Map<String, String>> classify(@RequestParam(required = false) String title,
                                                        @RequestParam(required = false) String description,
                                                        @RequestParam(required = false) String expected,
                                                        @RequestParam(required = false) String actual,
                                                        @RequestParam(required = false) String lob,
                                                        @RequestParam(required = false) String module) {
        try {
            AiService.Suggestion s = ai.classify(title, description, expected, actual, lob, module);
            return ResponseEntity.ok(Map.of("type", s.type(), "severity", s.severity(), "reason", s.reason()));
        } catch (AiClient.AiException e) {
            return ResponseEntity.unprocessableEntity().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/feedback/{id}/ai/duplicates")
    public String duplicates(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id, RedirectAttributes ra) {
        Feedback f = feedback.load(id, ctx.user(me));
        try {
            List<AiService.DuplicateHit> hits = ai.duplicates(f);
            if (hits.isEmpty()) ra.addFlashAttribute("ok", "AI found no likely duplicates of " + f.getCode() + ".");
            else ra.addFlashAttribute("aiDupes", hits);
        } catch (AiClient.AiException e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return "redirect:/feedback/" + id;
    }

    @PostMapping("/feedback/{id}/ai/story")
    public String draftStory(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id,
                             @RequestParam(required = false) String back, RedirectAttributes ra) {
        AppUser user = ctx.user(me);
        Feedback f = feedback.load(id, user);
        try {
            ai.draftStory(f, user);
            ra.addFlashAttribute("ok", "Drafted a user story for " + f.getCode() + ". Check and edit it before pushing to Jira.");
        } catch (AiClient.AiException e) {
            ra.addFlashAttribute("err", e.getMessage());
        }
        return "jira".equals(back) ? "redirect:/jira?preview=" + id : "redirect:/feedback/" + id + "#story";
    }

    @PostMapping("/feedback/{id}/story")
    public String saveStory(@AuthenticationPrincipal CurrentUser me, @PathVariable Long id,
                            @RequestParam(required = false) String story,
                            @RequestParam(required = false) String back, RedirectAttributes ra) {
        AppUser user = ctx.user(me);
        Feedback f = feedback.load(id, user);
        feedback.saveStory(f, user, story, "User story edited");
        ra.addFlashAttribute("ok", "Saved the user story for " + f.getCode() + ".");
        return "jira".equals(back) ? "redirect:/jira?preview=" + id : "redirect:/feedback/" + id + "#story";
    }
}
