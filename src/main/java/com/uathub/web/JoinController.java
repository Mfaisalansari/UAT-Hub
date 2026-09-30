package com.uathub.web;

import com.uathub.config.UatHubProperties;
import com.uathub.domain.AppUser;
import com.uathub.domain.Role;
import com.uathub.repo.AppUserRepository;
import com.uathub.service.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * The personal link. GET shows the welcome page (link previews in Teams/Outlook only ever GET,
 * so they can't sign anyone in). The Continue button POSTs, which sets the access cookie.
 */
@Controller
public class JoinController {

    private final AppUserRepository users;
    private final SettingsService settings;
    private final PasswordEncoder encoder;
    private final UatHubProperties props;

    public JoinController(AppUserRepository users, SettingsService settings, PasswordEncoder encoder, UatHubProperties props) {
        this.users = users;
        this.settings = settings;
        this.encoder = encoder;
        this.props = props;
    }

    @GetMapping("/join/{token}")
    public String show(@PathVariable String token, Model model) {
        Optional<AppUser> found = active(token);
        if (found.isEmpty()) return "revoked";
        AppUser u = found.get();
        String mode = !settings.requirePin() ? "welcome" : (u.getPinHash() == null ? "setPin" : "enterPin");
        model.addAttribute("person", u);
        model.addAttribute("token", token);
        model.addAttribute("mode", mode);
        model.addAttribute("projectNames", u.getRole() == Role.ADMIN ? "all projects"
                : String.join(", ", u.getProjects().stream().map(p -> p.getName()).sorted().toList()));
        return "join";
    }

    @PostMapping("/join/{token}")
    public String enter(@PathVariable String token,
                        @RequestParam(required = false) String pin,
                        @RequestParam(required = false) String pinConfirm,
                        HttpServletRequest request, HttpServletResponse response, RedirectAttributes ra) {
        Optional<AppUser> found = active(token);
        if (found.isEmpty()) return "revoked";
        AppUser u = found.get();

        if (settings.requirePin()) {
            if (u.getPinHash() == null) {
                if (pin == null || !pin.matches("\\d{4}")) {
                    ra.addFlashAttribute("err", "Choose a PIN of exactly 4 digits.");
                    return "redirect:/join/" + token;
                }
                if (!pin.equals(pinConfirm)) {
                    ra.addFlashAttribute("err", "The two PINs don't match.");
                    return "redirect:/join/" + token;
                }
                u.setPinHash(encoder.encode(pin));
            } else if (pin == null || !encoder.matches(pin, u.getPinHash())) {
                ra.addFlashAttribute("err", "That PIN doesn't match. Try again, or ask your admin to reset it.");
                return "redirect:/join/" + token;
            }
        }

        if (u.getLinkOpenedAt() == null) u.setLinkOpenedAt(Instant.now());
        u.setLastSeenAt(Instant.now());
        users.save(u);

        ResponseCookie cookie = ResponseCookie.from(props.cookieName(), token)
                .httpOnly(true)
                .secure(request.isSecure())
                .sameSite("Lax")
                .path("/")
                .maxAge(Duration.ofDays(props.cookieDays()))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return "redirect:" + home(u.getRole());
    }

    private Optional<AppUser> active(String token) {
        if (token == null || token.length() < 20) return Optional.empty();
        return users.findByAccessToken(token).filter(AppUser::isActive);
    }

    static String home(Role role) {
        return switch (role) {
            case BUSINESS -> "/review";
            case TESTER -> "/feedback/new";
            default -> "/";
        };
    }
}
