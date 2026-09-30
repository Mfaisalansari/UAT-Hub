package com.uathub.security;

import com.uathub.domain.AppUser;
import com.uathub.repo.AppUserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Replaces a login screen. The browser carries the person's access token in an HttpOnly cookie
 * (set once when they open their personal link). Every request is authenticated from it, so a
 * revoked or regenerated link stops working immediately.
 */
public class AccessCookieFilter extends OncePerRequestFilter {

    private final AppUserRepository users;
    private final String cookieName;

    public AccessCookieFilter(AppUserRepository users, String cookieName) {
        this.users = users;
        this.cookieName = cookieName;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String token = readToken(request);
        if (token != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            users.findByAccessToken(token).filter(AppUser::isActive).ifPresent(u -> {
                CurrentUser me = new CurrentUser(u.getId(), u.getName(), u.getRole());
                var auth = new UsernamePasswordAuthenticationToken(me, null,
                        List.of(new SimpleGrantedAuthority("ROLE_" + u.getRole().name())));
                SecurityContextHolder.getContext().setAuthentication(auth);
                touch(u);
            });
        }
        chain.doFilter(request, response);
    }

    private String readToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie c : cookies) {
            if (cookieName.equals(c.getName()) && c.getValue() != null && !c.getValue().isBlank()) {
                return c.getValue();
            }
        }
        return null;
    }

    private void touch(AppUser u) {
        Instant now = Instant.now();
        if (u.getLastSeenAt() == null || u.getLastSeenAt().isBefore(now.minus(Duration.ofMinutes(5)))) {
            u.setLastSeenAt(now);
            users.save(u);
        }
    }
}
