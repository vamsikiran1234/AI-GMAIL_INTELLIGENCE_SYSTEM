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

    // ─── generation ───────────────────────────────────────────────────────────

    public String generateWithFallback(String systemPrompt, String userPrompt) {
        try {
            return generateWithGemini(systemPrompt, userPrompt);
        } catch (Exception geminiException) {
            System.err.println("[AiOrchestratorService] Gemini failed, using local fallback: " + geminiException.getMessage());
            return localFallback(systemPrompt, userPrompt);
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
                .block(), "NVIDIA NIM generate content", 1);
        return response.path("choices").path(0).path("message").path("content").asText("");
    }

    // ─── embeddings ───────────────────────────────────────────────────────────

    public List<Double> embed(String content) {
        try {
            JsonNode response = rateLimitExecutor.execute(() -> geminiWebClient.post()
                    .uri(uriBuilder -> uriBuilder.path("/v1beta/models/text-embedding-004:embedContent")
                            .queryParam("key", properties.ai().gemini().apiKey())
                            .build())
                    .bodyValue(java.util.Map.of(
                            "content", java.util.Map.of("parts", List.of(java.util.Map.of("text", content)))))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(), "Gemini embed content", 1);
            JsonNode values = response.path("embedding").path("values");
            java.util.List<Double> embedding = new java.util.ArrayList<>();
            values.forEach(value -> embedding.add(value.asDouble()));
            if (!embedding.isEmpty()) {
                return embedding;
            }
        } catch (Exception exception) {
            System.err.println("[AiOrchestratorService] Falling back to local embedding: " + exception.getMessage());
        }
        return localEmbedding(content);
    }

    // ─── high-level AI operations ─────────────────────────────────────────────

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

    /**
     * Semantic RAG fallback — answers from pgvector evidence when the structured
     * inbox search returned nothing.
     */
    public String answerQuestion(String question, String evidenceBundle) {
        String systemPrompt = "You answer strictly from the provided email evidence. If the evidence is insufficient, say so.";
        String userPrompt = "Question: " + question + "\n\nEmail evidence:\n" + evidenceBundle;
        return generateWithFallback(systemPrompt, userPrompt);
    }

    /**
     * Primary inbox-search answer method.
     *
     * The model is told it has ALREADY searched the inbox and received real results.
     * The prompt adapts based on the user's intent so job queries get a different
     * format from finance queries, general queries, etc.
     */
    public String answerFromInboxSearch(String question, String evidenceBundle,
            int resultCount, String dateRangeLabel, InboxQueryParser.Intent intent) {

        // ── Intent-specific formatting instruction ─────────────────────────────
        String formatInstruction = switch (intent) {
            case JOB_SEARCH -> """
                    For each email, provide:
                    • Company / sender name
                    • Role / job title (from subject or content)
                    • Date received
                    • Key details found in the email: location, experience required, salary if mentioned
                    List results sorted by most recent first.
                    """;
            case FINANCE -> """
                    For each email, provide:
                    • Sender / institution
                    • Transaction type or subject
                    • Date
                    • Amount or key financial detail if present
                    List results sorted by most recent first.
                    """;
            case NEWSLETTER -> """
                    Summarise the key topics and stories from the newsletters found.
                    Group by newsletter source if multiple sources are present.
                    """;
            case WORK -> """
                    For each email, provide:
                    • Sender and subject
                    • Date
                    • Key action items or decisions mentioned
                    """;
            default -> """
                    Summarise the emails clearly and concisely.
                    List by most recent first.
                    """;
        };

        String systemPrompt = """
                You are an intelligent email inbox assistant.
                You have already searched the user's inbox. The emails below are the actual search results.

                Core rules:
                1. NEVER say "no email evidence provided" or ask the user to provide evidence.
                2. If the result count is 0, say naturally: "I couldn't find any matching emails from [date range]."
                3. Do NOT expose internal framing like "Search context", "Evidence bundle", or email index numbers like [1], [2].
                4. Do NOT hallucinate any detail not present in the emails below.
                5. If a detail is missing (e.g. location not in the email), omit it — do not guess.
                6. Be concise. The user wants a direct, readable answer — not a data dump.
                """
                + "\nResponse format for this request:\n" + formatInstruction;

        String userPrompt = "User request: " + question + "\n"
                + "Date range searched: " + dateRangeLabel + "\n"
                + "Emails found: " + resultCount + "\n\n"
                + evidenceBundle;

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

    // ─── private builders ─────────────────────────────────────────────────────

    private java.util.Map<String, Object> buildGenerateContentPayload(String systemPrompt, String userPrompt) {
        return java.util.Map.of(
                "systemInstruction", java.util.Map.of("parts", List.of(java.util.Map.of("text", systemPrompt))),
                "contents", List.of(java.util.Map.of("role", "user",
                        "parts", List.of(java.util.Map.of("text", userPrompt)))));
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

    // ─── local fallbacks (no network) ─────────────────────────────────────────

    private String localFallback(String systemPrompt, String userPrompt) {
        String prompt = (systemPrompt + "\n" + userPrompt).toLowerCase(Locale.ROOT);

        if (prompt.contains("classify the email")) {
            return classifyLocally(userPrompt);
        }
        if (prompt.contains("extract unique news items")) {
            return userPrompt.length() > 500 ? userPrompt.substring(0, 500) : userPrompt;
        }
        if (prompt.contains("draft professional emails") || prompt.contains("draft email")) {
            return "I'm unable to generate a model draft right now, but here is a concise draft based on your prompt: "
                    + shorten(userPrompt, 700);
        }
        // Inbox search local fallback — surface the raw evidence so the user still sees results
        if (prompt.contains("intelligent email inbox assistant")) {
            // Find the evidence block (starts after the metadata lines)
            int evidenceStart = userPrompt.indexOf("\n\n");
            String evidence = evidenceStart >= 0
                    ? userPrompt.substring(evidenceStart).strip()
                    : userPrompt;
            return "Here are the emails I found:\n\n" + shorten(evidence, 1200);
        }
        if (prompt.contains("answer strictly from the provided email evidence")) {
            return "I'm unable to generate a model answer right now. Please retry after the AI provider recovers.";
        }
        if (prompt.contains("summarize email threads")) {
            return "Summary unavailable from the AI provider at the moment. Please retry once the model service is available.";
        }
        return shorten(userPrompt, 500);
    }

    private String classifyLocally(String userPrompt) {
        String text = userPrompt.toLowerCase(Locale.ROOT);
        if (containsAny(text, "receipt", "invoice", "payment", "refund", "bank", "card"))
            return "Finance";
        if (containsAny(text, "interview", "resume", "application", "hiring", "recruiter", "candidate"))
            return "Job / Recruitment";
        if (containsAny(text, "newsletter", "unsubscribe", "digest", "weekly", "roundup"))
            return "Newsletters";
        if (containsAny(text, "no-reply", "noreply", "notification", "alert", "security", "verification"))
            return "Notifications";
        if (containsAny(text, "meeting", "project", "deadline", "team", "client", "proposal"))
            return "Work / Professional";
        return "Personal";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) return true;
        }
        return false;
    }

    private String shorten(String value, int maxLength) {
        if (value == null || value.isBlank()) return "";
        return value.length() <= maxLength ? value : value.substring(0, maxLength - 3) + "...";
    }

    private List<Double> localEmbedding(String content) {
        int dimensions = 768;
        double[] vector = new double[dimensions];
        if (content == null || content.isBlank()) {
            java.util.List<Double> emptyVector = new java.util.ArrayList<>(dimensions);
            for (int i = 0; i < dimensions; i++) emptyVector.add(0.0);
            return emptyVector;
        }
        String normalized = content.toLowerCase(Locale.ROOT);
        for (String token : normalized.split("[^a-z0-9]+")) {
            if (token.isBlank()) continue;
            int index = Math.floorMod(token.hashCode(), dimensions);
            vector[index] += 1.0;
        }
        java.util.List<Double> embedding = new java.util.ArrayList<>(dimensions);
        for (double value : vector) embedding.add(value);
        return embedding;
    }
}
