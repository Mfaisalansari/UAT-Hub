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
        Jira jira,
        Notify notifications,
        Ai ai) {

    public record Notify(String mailFrom, Boolean digestEnabled, String digestCron) {}

    /** provider: none, anthropic, or openai (any OpenAI-compatible chat completions endpoint). */
    public record Ai(String provider, String apiKey, String model, String baseUrl) {
        public boolean enabled() {
            String p = provider == null ? "none" : provider.trim().toLowerCase();
            if (p.equals("anthropic")) return apiKey != null && !apiKey.isBlank();
            if (p.equals("openai")) return baseUrl != null && !baseUrl.isBlank();
            return false;
        }

        public boolean anthropic() {
            return "anthropic".equalsIgnoreCase(provider == null ? "" : provider.trim());
        }
    }

    public Notify notifySafe() {
        return notifications == null ? new Notify(null, true, null) : notifications;
    }

    public Ai aiSafe() {
        return ai == null ? new Ai("none", null, null, null) : ai;
    }

    /** Absolute link into the app for emails and Teams messages. */
    public String url(String path) {
        return baseUrl.replaceAll("/+$", "") + (path.startsWith("/") ? path : "/" + path);
    }

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
