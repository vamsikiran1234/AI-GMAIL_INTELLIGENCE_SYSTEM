package com.repeatless.gmailintelligence.service;

import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repeatless.gmailintelligence.config.AppProperties;

@Service
public class AiOrchestratorService {

    private final WebClient geminiWebClient;
    private final WebClient nimWebClient;
    private final AppProperties properties;
    private final RateLimitExecutor rateLimitExecutor;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiOrchestratorService(WebClient geminiWebClient, WebClient nimWebClient, AppProperties properties,
            RateLimitExecutor rateLimitExecutor) {
        this.geminiWebClient = geminiWebClient;
        this.nimWebClient = nimWebClient;
        this.properties = properties;
        this.rateLimitExecutor = rateLimitExecutor;
    }

    public String generateWithFallback(String systemPrompt, String userPrompt) {
        try {
            return generateWithGemini(systemPrompt, userPrompt);
        } catch (Exception exception) {
            return generateWithNim(systemPrompt, userPrompt);
        }
    }

    public String generateWithGemini(String systemPrompt, String userPrompt) {
        JsonNode response = rateLimitExecutor.execute(() -> geminiWebClient.post()
                .uri(uriBuilder -> uriBuilder.path("/v1beta/models/{model}:generateContent")
                        .queryParam("key", properties.ai().gemini().apiKey())
                        .build(properties.ai().gemini().model()))
                .bodyValue(buildGenerateContentPayload(systemPrompt, userPrompt))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(), "Gemini generate content");
        return response.path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
    }

    public String generateWithNim(String systemPrompt, String userPrompt) {
        JsonNode response = rateLimitExecutor.execute(() -> nimWebClient.post()
                .uri(uriBuilder -> uriBuilder.path("/v1/chat/completions").build())
                .headers(headers -> headers.setBearerAuth(properties.ai().nim().apiKey()))
                .bodyValue(buildNimPayload(systemPrompt, userPrompt))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(), "NVIDIA NIM generate content");
        return response.path("choices").path(0).path("message").path("content").asText("");
    }

    public List<Double> embed(String content) {
        JsonNode response = rateLimitExecutor.execute(() -> geminiWebClient.post()
                .uri(uriBuilder -> uriBuilder.path("/v1beta/models/text-embedding-004:embedContent")
                        .queryParam("key", properties.ai().gemini().apiKey())
                        .build())
                .bodyValue(java.util.Map.of(
                        "content", java.util.Map.of("parts", List.of(java.util.Map.of("text", content)))))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(), "Gemini embed content");
        JsonNode values = response.path("embedding").path("values");
        java.util.List<Double> embedding = new java.util.ArrayList<>();
        values.forEach(value -> embedding.add(value.asDouble()));
        return embedding;
    }

    public String summarizeThread(String threadTranscript) {
        String systemPrompt = "You summarize email threads into concise, factual notes with source clarity.";
        String userPrompt = "Summarize this email thread. Preserve the key decisions, action items, deadlines, and unresolved questions.\n\n"
                + threadTranscript;
        return generateWithFallback(systemPrompt, userPrompt);
    }

    public String draftEmail(String prompt, String threadContext, String mode) {
        String systemPrompt = "You draft professional emails. Avoid hallucinations. Match the user's intent and tone.";
        String userPrompt = "Mode: " + mode + "\nPrompt: " + prompt + "\n\nThread context:\n" + threadContext;
        return generateWithFallback(systemPrompt, userPrompt);
    }

    public String answerQuestion(String question, String evidenceBundle) {
        String systemPrompt = "You answer strictly from the provided email evidence. If the evidence is insufficient, say so.";
        String userPrompt = "Question: " + question + "\n\nEmail evidence:\n" + evidenceBundle;
        return generateWithFallback(systemPrompt, userPrompt);
    }

    public String categorizeEmail(String emailText) {
        String systemPrompt = "Classify the email into one of: Newsletters, Job / Recruitment, Finance, Notifications, Personal, Work / Professional.";
        String userPrompt = "Email content:\n" + emailText;
        return generateWithFallback(systemPrompt, userPrompt).toUpperCase(Locale.ROOT);
    }

    public String deduplicateNewsletterItems(String newsletterBundle) {
        String systemPrompt = "Extract unique news items and remove duplicates across newsletters while preserving source attribution.";
        String userPrompt = newsletterBundle;
        return generateWithFallback(systemPrompt, userPrompt);
    }

    private java.util.Map<String, Object> buildGenerateContentPayload(String systemPrompt, String userPrompt) {
        return java.util.Map.of(
                "systemInstruction", java.util.Map.of("parts", List.of(java.util.Map.of("text", systemPrompt))),
                "contents", List.of(java.util.Map.of("role", "user", "parts", List.of(java.util.Map.of("text", userPrompt)))));
    }

    private java.util.Map<String, Object> buildNimPayload(String systemPrompt, String userPrompt) {
        return java.util.Map.of(
                "model", properties.ai().nim().model(),
                "messages", List.of(
                        java.util.Map.of("role", "system", "content", systemPrompt),
                        java.util.Map.of("role", "user", "content", userPrompt)),
                "temperature", 0.2,
                "max_tokens", 1024);
    }
}
