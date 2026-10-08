package com.uathub.security;

import com.uathub.config.UatHubProperties;
import com.uathub.repo.AppUserRepository;
import jakarta.servlet.DispatcherType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, AppUserRepository users, UatHubProperties props) throws Exception {
        http
            .addFilterBefore(new AccessCookieFilter(users, props.cookieName()), UsernamePasswordAuthenticationFilter.class)
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.FORWARD, DispatcherType.ERROR).permitAll()
                .requestMatchers("/join/**", "/css/**", "/js/**", "/favicon.svg", "/no-access", "/error").permitAll()
                .requestMatchers("/team/**", "/projects/**").hasRole("ADMIN")
                .requestMatchers("/jira/**", "/import").hasAnyRole("QA_LEAD", "ADMIN")
                .requestMatchers("/review/**").hasAnyRole("BUSINESS", "ADMIN")
                .requestMatchers("/feedback/new").hasAnyRole("TESTER", "QA_LEAD", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/feedback").hasAnyRole("TESTER", "QA_LEAD", "ADMIN")
                .requestMatchers("/feedback/*/triage", "/feedback/bulk", "/feedback/*/ai/**", "/feedback/*/story").hasAnyRole("QA_LEAD", "ADMIN")
                .requestMatchers("/api/ai/**").hasAnyRole("TESTER", "QA_LEAD", "ADMIN")
                .requestMatchers("/scenarios/**", "/cycles/**").hasAnyRole("QA_LEAD", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/runs/signoff").hasAnyRole("BUSINESS", "ADMIN")
                .requestMatchers("/runs/*/assign", "/runs/*/export").hasAnyRole("QA_LEAD", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/runs", "/runs/**").hasAnyRole("QA_LEAD", "ADMIN")
                .anyRequest().authenticated())
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .logout(l -> l.disable())
            .exceptionHandling(e -> e
                .authenticationEntryPoint((req, res, ex) -> res.sendRedirect(req.getContextPath() + "/no-access"))
                .accessDeniedPage("/forbidden"));
        return http.build();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
