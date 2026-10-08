package com.uathub.web;

import com.uathub.config.UatHubProperties;
import com.uathub.security.CurrentUser;
import com.uathub.service.CycleService;
import com.uathub.service.ProjectContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class PagesController {

    private final ProjectContext ctx;
    private final UatHubProperties props;
    private final CycleService cycles;

    public PagesController(ProjectContext ctx, UatHubProperties props, CycleService cycles) {
        this.ctx = ctx;
        this.props = props;
        this.cycles = cycles;
    }

    /** Switch the UAT cycle being viewed (sidebar picker). */
    @PostMapping("/cycle/select")
    public String selectCycle(@AuthenticationPrincipal CurrentUser me, HttpSession session,
                              @RequestParam Long id, HttpServletRequest req) {
        cycles.select(ctx.require(me, session), session, id);
        String back = GlobalModel.sameSitePath(req.getHeader("Referer"));
        return "redirect:" + (back.startsWith("/runs/") || back.startsWith("/exec/") ? "/runs" : back);
    }

    @GetMapping("/no-access")
    public String noAccess() {
        return "no-access";
    }

    @RequestMapping("/forbidden")
    public String forbidden() {
        return "forbidden";
    }

    @PostMapping("/project/select")
    public String selectProject(@AuthenticationPrincipal CurrentUser me, HttpSession session,
                                @RequestParam Long id, HttpServletRequest req) {
        ctx.select(me, session, id);
        String back = GlobalModel.sameSitePath(req.getHeader("Referer"));
        // Item pages belong to one project, so go to the register after switching.
        return "redirect:" + (back.startsWith("/feedback/") || back.startsWith("/review") ? "/" : back);
    }

    /** Forget this browser. The person's link still works if they open it again. */
    @PostMapping("/leave")
    public String leave(HttpServletResponse response, HttpSession session) {
        ResponseCookie gone = ResponseCookie.from(props.cookieName(), "").path("/").maxAge(0).build();
        response.addHeader(HttpHeaders.SET_COOKIE, gone.toString());
        session.invalidate();
        return "redirect:/no-access?left";
    }
}
