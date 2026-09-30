package com.uathub.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

@ConfigurationProperties(prefix = "uathub")
public record UatHubProperties(
        String baseUrl,
        String dataDir,
        String cookieName,
        int cookieDays,
        List<String> lobs,
        List<String> divisions,
        Jira jira) {

    public record Jira(String baseUrl, String deployment, String email, String token) {
        public boolean configured() {
            return baseUrl != null && !baseUrl.isBlank() && token != null && !token.isBlank();
        }

        public boolean cloud() {
            return !"DATA_CENTER".equalsIgnoreCase(deployment);
        }

        public String cleanBaseUrl() {
            return baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
        }
    }

    public String linkFor(String token) {
        return baseUrl.replaceAll("/+$", "") + "/join/" + token;
    }
}
