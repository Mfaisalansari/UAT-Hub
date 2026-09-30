package com.uathub.web;

import com.uathub.config.UatHubProperties;
import com.uathub.domain.Project;
import com.uathub.domain.Stage;
import com.uathub.repo.FeedbackRepository;
import com.uathub.security.CurrentUser;
import com.uathub.service.ProjectContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.support.RequestContextUtils;

import java.net.URI;

/** Adds the signed-in person, current project and nav counts to every page. */
@ControllerAdvice
public class GlobalModel {

    private final ProjectContext ctx;
    private final FeedbackRepository feedback;
    private final UatHubProperties props;

    public GlobalModel(ProjectContext ctx, FeedbackRepository feedback, UatHubProperties props) {
        this.ctx = ctx;
        this.feedback = feedback;
        this.props = props;
    }

    @ModelAttribute
    public void common(Model model, @AuthenticationPrincipal CurrentUser me, HttpSession session) {
        model.addAttribute("lobs", props.lobs());
        model.addAttribute("divisions", props.divisions());
        if (me == null) return;
        model.addAttribute("me", me);
        Project p = ctx.current(me, session);
        model.addAttribute("project", p);
        model.addAttribute("myProjects", ctx.accessible(me));
        if (p != null) {
            model.addAttribute("navTotal", feedback.countByProject(p));
            model.addAttribute("navReview", feedback.countByProjectAndStage(p, Stage.BUSINESS_REVIEW));
            model.addAttribute("navJira", feedback.countByProjectAndStage(p, Stage.DECIDED));
        }
    }

    /** Validation problems from services come back to the same page as a message. */
    @ExceptionHandler(IllegalArgumentException.class)
    public String badInput(IllegalArgumentException e, HttpServletRequest req) {
        return back(req, e.getMessage());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public String tooLarge(HttpServletRequest req) {
        return back(req, "That file is too large. Each file can be up to 10 MB.");
    }

    static String back(HttpServletRequest req, String message) {
        RequestContextUtils.getOutputFlashMap(req).put("err", message);
        return "redirect:" + sameSitePath(req.getHeader("Referer"));
    }

    static String sameSitePath(String referer) {
        if (referer == null) return "/";
        try {
            URI u = URI.create(referer);
            String path = u.getRawPath() == null || u.getRawPath().isBlank() ? "/" : u.getRawPath();
            return u.getRawQuery() == null ? path : path + "?" + u.getRawQuery();
        } catch (IllegalArgumentException e) {
            return "/";
        }
    }
}
