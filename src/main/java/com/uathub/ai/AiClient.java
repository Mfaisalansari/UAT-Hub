package com.uathub.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uathub.config.UatHubProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

/**
 * One text-in, text-out call to the configured model.
 * anthropic: Claude Messages API. openai: any OpenAI-compatible /chat/completions endpoint
 * (a company AI gateway, OpenAI, or a local model server such as Ollama).
 */
@Component
public class AiClient {

    public static class AiException extends RuntimeException {
        public AiException(String message) { super(message); }
    }

    private final UatHubProperties.Ai cfg;
    private final ObjectMapper json = new ObjectMapper();
    private final RestClient http;

    public AiClient(UatHubProperties props) {
        this.cfg = props.aiSafe();
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(60_000);
        this.http = RestClient.builder().requestFactory(f).build();
    }

    public boolean enabled() {
        return cfg.enabled();
    }

    public String complete(String system, String user, int maxTokens) {
        if (!enabled()) throw new AiException("AI assist is not configured");
        try {
            return cfg.anthropic() ? anthropic(system, user, maxTokens) : openai(system, user, maxTokens);
        } catch (RestClientResponseException e) {
            throw new AiException("The AI service answered " + e.getStatusCode().value() + ". Check the AI settings.");
        } catch (AiException e) {
            throw e;
        } catch (Exception e) {
            throw new AiException("Couldn't reach the AI service: " + e.getMessage());
        }
    }

    private String anthropic(String system, String user, int maxTokens) throws Exception {
        String base = blank(cfg.baseUrl()) ? "https://api.anthropic.com" : cfg.baseUrl().replaceAll("/+$", "");
        String model = blank(cfg.model()) ? "claude-sonnet-5-5" : cfg.model().trim();
        String body = http.post().uri(base + "/v1/messages")
                .header("x-api-key", cfg.apiKey())
                .header("anthropic-version", "2023-06-01")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("model", model, "max_tokens", maxTokens, "system", system,
                        "messages", List.of(Map.of("role", "user", "content", user))))
                .retrieve().body(String.class);
        StringBuilder out = new StringBuilder();
        for (JsonNode block : json.readTree(body).path("content")) {
            if ("text".equals(block.path("type").asText())) out.append(block.path("text").asText());
        }
        return out.toString();
    }

    private String openai(String system, String user, int maxTokens) throws Exception {
        String base = cfg.baseUrl().replaceAll("/+$", "");
        String model = blank(cfg.model()) ? "gpt-4o-mini" : cfg.model().trim();
        RestClient.RequestBodySpec req = http.post().uri(base + "/chat/completions").contentType(MediaType.APPLICATION_JSON);
        if (!blank(cfg.apiKey())) req = req.header("Authorization", "Bearer " + cfg.apiKey());
        String body = req.body(Map.of("model", model, "max_tokens", maxTokens, "messages", List.of(
                        Map.of("role", "system", "content", system), Map.of("role", "user", "content", user))))
                .retrieve().body(String.class);
        return json.readTree(body).path("choices").path(0).path("message").path("content").asText("");
    }

    /** Pulls the JSON object out of a reply, tolerating code fences or a sentence around it. */
    public JsonNode jsonObject(String reply) {
        int a = reply.indexOf('{'), b = reply.lastIndexOf('}');
        if (a < 0 || b <= a) throw new AiException("The AI reply wasn't in the expected format. Try again.");
        try {
            return json.readTree(reply.substring(a, b + 1));
        } catch (Exception e) {
            throw new AiException("The AI reply wasn't in the expected format. Try again.");
        }
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
