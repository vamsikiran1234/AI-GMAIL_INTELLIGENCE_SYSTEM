package com.repeatless.gmailintelligence.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import com.repeatless.gmailintelligence.config.AppProperties;
import com.repeatless.gmailintelligence.model.GmailModels.GmailMessageSnapshot;
import com.repeatless.gmailintelligence.model.GmailModels.GmailThreadSnapshot;

@Service
public class GmailApiClient {

    private final WebClient gmailWebClient;
    private final WebClient googleOauthWebClient;
    private final AppProperties properties;
    private final RateLimitExecutor rateLimitExecutor;
    private final EmailContentCleaner contentCleaner;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GmailApiClient(WebClient gmailWebClient, WebClient googleOauthWebClient, AppProperties properties,
            RateLimitExecutor rateLimitExecutor, EmailContentCleaner contentCleaner) {
        this.gmailWebClient = gmailWebClient;
        this.googleOauthWebClient = googleOauthWebClient;
        this.properties = properties;
        this.rateLimitExecutor = rateLimitExecutor;
        this.contentCleaner = contentCleaner;
    }

    public String buildAuthorizationUrl(String userId) {
        String scope = String.join(" ", List.of(
                "https://www.googleapis.com/auth/gmail.readonly",
                "https://www.googleapis.com/auth/gmail.send",
                "https://www.googleapis.com/auth/gmail.modify"));
        return "https://accounts.google.com/o/oauth2/v2/auth?client_id=" + properties.gmail().clientId()
                + "&redirect_uri=" + encode(properties.gmail().redirectUri())
                + "&response_type=code&scope=" + encode(scope)
                + "&access_type=offline&prompt=consent&include_granted_scopes=true&state=" + encode(userId);
    }

    public OAuthTokenResponse exchangeAuthorizationCode(String authorizationCode) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", authorizationCode);
        form.add("client_id", properties.gmail().clientId());
        form.add("client_secret", properties.gmail().clientSecret());
        form.add("redirect_uri", properties.gmail().redirectUri());
        form.add("grant_type", "authorization_code");

        return rateLimitExecutor.execute(() -> googleOauthWebClient.post()
                .uri("/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(OAuthTokenResponse.class)
                .block(), "Google OAuth token exchange");
    }

    public OAuthTokenResponse refreshAccessToken(String refreshToken) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", properties.gmail().clientId());
        form.add("client_secret", properties.gmail().clientSecret());
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");

        return rateLimitExecutor.execute(() -> googleOauthWebClient.post()
                .uri("/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(OAuthTokenResponse.class)
                .block(), "Google OAuth refresh");
    }

    /**
     * Returns true when the exception signals an expired / revoked OAuth token.
     */
    public static boolean isAuthError(Exception ex) {
        if (ex instanceof GmailApiException gae) {
            return gae.isUnauthorized();
        }
        String msg = ex.getMessage();
        if (msg == null) return false;
        return msg.contains("401") || msg.contains("UNAUTHENTICATED")
                || msg.contains("Invalid Credentials") || msg.contains("authError");
    }

    public GmailThreadSnapshot fetchThread(String accessToken, String threadId) {
        String responseBody = rateLimitExecutor.execute(() -> gmailWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/users/me/threads/{threadId}")
                        .queryParam("format", "full")
                        .queryParam("metadataHeaders", "Message-ID")
                        .queryParam("metadataHeaders", "In-Reply-To")
                        .queryParam("metadataHeaders", "References")
                        .build(threadId))
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(String.class)
                .block(), "Gmail fetch thread", 1);
        if (responseBody == null || responseBody.isBlank()) {
            throw new IllegalStateException("Empty Gmail fetch thread response for thread " + threadId);
        }
        try {
            JsonNode response = objectMapper.readTree(responseBody);
            // Surface a clear auth error so the sync layer can refresh and retry
            JsonNode errorCode = response.path("error").path("code");
            if (!errorCode.isMissingNode() && errorCode.asInt() == 401) {
                throw new GmailApiException("401 UNAUTHENTICATED - Gmail fetch thread " + threadId
                        + ": " + response.path("error").path("message").asText(), null, 401);
            }
            if (!errorCode.isMissingNode() && errorCode.asInt() == 404) {
                throw new GmailApiException("404 NOT_FOUND - Gmail fetch thread " + threadId, null, 404);
            }
            return parseThread(response);
        } catch (GmailApiException ex) {
            throw ex;
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception exception) {
            String preview = responseBody.length() > 200 ? responseBody.substring(0, 200) + "..." : responseBody;
            throw new IllegalStateException("Gmail fetch thread returned non-JSON for thread " + threadId + ": " + preview, exception);
        }
    }

    public GmailThreadSnapshot fetchThreadOrFallback(String accessToken, JsonNode threadNode) {
        String threadId = threadNode.path("id").asText();
        try {
            return fetchThread(accessToken, threadId);
        } catch (GmailApiException gae) {
            if (gae.isNotFound()) {
                System.err.println("[GmailApiClient] Thread " + threadId + " not found (deleted/trashed) — skipping");
                return buildFallbackThreadSnapshot(threadNode);
            }
            throw gae;
        } catch (Exception exception) {
            System.err.println("[GmailApiClient.fetchThreadOrFallback] Using fallback for thread " + threadId + ": " + exception.getMessage());
            return buildFallbackThreadSnapshot(threadNode);
        }
    }

    public GmailThreadSnapshot fetchThreadOrFallback(String accessToken, String threadId) {
        try {
            return fetchThread(accessToken, threadId);
        } catch (GmailApiException gae) {
            if (gae.isNotFound()) {
                System.err.println("[GmailApiClient] Thread " + threadId + " not found (deleted/trashed) — skipping");
                return buildFallbackThreadSnapshot(threadId, threadId, "[]");
            }
            throw gae;
        } catch (Exception exception) {
            System.err.println("[GmailApiClient.fetchThreadOrFallback] Using fallback for thread " + threadId + ": " + exception.getMessage());
            return buildFallbackThreadSnapshot(threadId, threadId, "[]");
        }
    }

    public ThreadPage listThreadsPage(String accessToken, String pageToken, int pageSize) {
        JsonNode response = rateLimitExecutor.execute(() -> gmailWebClient.get()
                .uri(uriBuilder -> {
                    var builder = uriBuilder.path("/users/me/threads")
                            .queryParam("maxResults", pageSize)
                            .queryParam("q", "in:inbox");
                    if (pageToken != null && !pageToken.isBlank()) {
                        builder.queryParam("pageToken", pageToken);
                    }
                    return builder.build();
                })
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(), "Gmail list threads");

        // Surface auth errors clearly so the caller can refresh and retry
        if (response != null && !response.path("error").isMissingNode()) {
            int code = response.path("error").path("code").asInt(0);
            String msg = response.path("error").path("message").asText("");
            if (code == 401) {
                throw new IllegalStateException("401 UNAUTHENTICATED - Gmail list threads: " + msg);
            }
        }

        List<GmailThreadSnapshot> snapshots = new ArrayList<>();
        JsonNode threads = response.path("threads");
        if (threads.isArray()) {
            for (JsonNode threadNode : threads) {
                snapshots.add(fetchThreadOrFallback(accessToken, threadNode));
            }
        }
        return new ThreadPage(snapshots, response.path("nextPageToken").asText(""));
    }

    public HistoryPage listThreadIdsFromHistory(String accessToken, String historyId, String pageToken) {
        JsonNode response = rateLimitExecutor.execute(() -> gmailWebClient.get()
                .uri(uriBuilder -> uriBuilder.path("/users/me/history")
                        .queryParam("startHistoryId", historyId)
                        .queryParam("historyTypes", "messageAdded")
                        .queryParam("historyTypes", "labelAdded")
                        .queryParam("historyTypes", "messageDeleted")
                        .queryParamIfPresent("pageToken", java.util.Optional.ofNullable(pageToken).filter(token -> !token.isBlank()))
                        .build())
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(), "Gmail history list");

        // Surface auth errors so the caller can refresh and retry
        if (response != null && !response.path("error").isMissingNode()) {
            int code = response.path("error").path("code").asInt(0);
            String msg = response.path("error").path("message").asText("");
            if (code == 401) {
                throw new IllegalStateException("401 UNAUTHENTICATED - Gmail history list: " + msg);
            }
        }

        List<String> threadIds = new ArrayList<>();
        JsonNode histories = response.path("history");
        if (histories.isArray()) {
            for (JsonNode history : histories) {
                collectThreadIds(history.path("messagesAdded"), threadIds);
                collectThreadIds(history.path("labelsAdded"), threadIds);
                collectThreadIds(history.path("messagesDeleted"), threadIds);
            }
        }
        return new HistoryPage(threadIds.stream().distinct().toList(), response.path("nextPageToken").asText(""), response.path("historyId").asText(historyId));
    }

    public String sendMessage(String accessToken, String rawMimeMessageBase64Url) {
        JsonNode response = rateLimitExecutor.execute(() -> gmailWebClient.post()
                .uri("/users/me/messages/send")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("raw", rawMimeMessageBase64Url))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(), "Gmail send message");
        return response.path("id").asText();
    }

    public String buildRawReply(String fromAddress, String toAddress, String subject, String bodyText, String messageId,
            String references) {
        String formattedSubject = subject != null && subject.startsWith("Re:") ? subject : "Re: " + subject;
        StringBuilder builder = new StringBuilder();
        builder.append("To: ").append(toAddress).append("\r\n");
        builder.append("From: ").append(fromAddress).append("\r\n");
        builder.append("Subject: ").append(formattedSubject).append("\r\n");
        builder.append("In-Reply-To: ").append(messageId).append("\r\n");
        if (references != null && !references.isBlank()) {
            builder.append("References: ").append(references).append("\r\n");
        }
        builder.append("MIME-Version: 1.0\r\n");
        builder.append("Content-Type: text/plain; charset=UTF-8\r\n\r\n");
        builder.append(bodyText);
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(builder.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    public String getUserProfileEmail(String accessToken) {
        JsonNode response = rateLimitExecutor.execute(() -> gmailWebClient.get()
                .uri("/users/me/profile")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(), "Gmail user profile");
        return response.path("emailAddress").asText();
    }

    private GmailThreadSnapshot parseThread(JsonNode threadNode) {
        List<GmailMessageSnapshot> messages = new ArrayList<>();
        JsonNode messagesNode = threadNode.path("messages");
        if (messagesNode.isArray()) {
            for (JsonNode messageNode : messagesNode) {
                messages.add(parseMessage(messageNode, threadNode.path("id").asText()));
            }
        }
        return new GmailThreadSnapshot(
                threadNode.path("id").asText(),
                threadNode.path("historyId").asText(),
                extractHeader(threadNode.path("messages").path(0).path("payload").path("headers"), "Subject"),
                threadNode.path("messages").isArray() && threadNode.path("messages").size() > 0
                        ? threadNode.path("messages").path(0).path("labelIds").toString()
                        : "[]",
                Instant.ofEpochMilli(threadNode.path("messages").isArray() && threadNode.path("messages").size() > 0
                        ? threadNode.path("messages").path(threadNode.path("messages").size() - 1).path("internalDate").asLong()
                        : System.currentTimeMillis()),
                messages);
    }

    private GmailThreadSnapshot buildFallbackThreadSnapshot(JsonNode threadNode) {
        return buildFallbackThreadSnapshot(
                threadNode.path("id").asText(),
                threadNode.path("snippet").asText(threadNode.path("id").asText()),
                threadNode.path("labelIds").isArray() ? threadNode.path("labelIds").toString() : "[]");
    }

    private GmailThreadSnapshot buildFallbackThreadSnapshot(String threadId, String subject, String labelIds) {
        return new GmailThreadSnapshot(
                threadId,
                "",
                subject == null || subject.isBlank() ? threadId : subject,
                labelIds == null || labelIds.isBlank() ? "[]" : labelIds,
                Instant.now(),
                List.of());
    }

    private GmailMessageSnapshot parseMessage(JsonNode messageNode, String threadId) {
        JsonNode payload = messageNode.path("payload");
        JsonNode headers = payload.path("headers");
        String rawBodyText = extractBodyText(payload);
        String rawBodyHtml = extractBodyHtml(payload);
        // Produce clean plain text at ingest time so all downstream consumers
        // (AI evidence builder, citations, keyword search) receive readable text.
        String cleanBodyText = contentCleaner.extractCleanText(rawBodyText, rawBodyHtml);
        return new GmailMessageSnapshot(
                messageNode.path("id").asText(),
                threadId,
                extractHeader(headers, "Message-ID"),
                extractHeader(headers, "In-Reply-To"),
                extractHeader(headers, "References"),
                extractHeader(headers, "From"),
                extractHeader(headers, "To"),
                extractHeader(headers, "Cc"),
                extractHeader(headers, "Subject"),
                Instant.ofEpochMilli(messageNode.path("internalDate").asLong()),
                cleanBodyText,
                rawBodyHtml,   // preserve raw HTML separately for future use
                messageNode.path("internalDate").asLong());
    }

    private void collectThreadIds(JsonNode node, List<String> threadIds) {
        if (node.isArray()) {
            for (JsonNode message : node) {
                if (message.has("message") && message.path("message").has("threadId")) {
                    threadIds.add(message.path("message").path("threadId").asText());
                }
                if (message.has("threadId")) {
                    threadIds.add(message.path("threadId").asText());
                }
            }
        }
    }

    private String extractHeader(JsonNode headers, String headerName) {
        if (headers.isArray()) {
            for (JsonNode header : headers) {
                if (headerName.equalsIgnoreCase(header.path("name").asText())) {
                    return header.path("value").asText();
                }
            }
        }
        return "";
    }

    private String extractBodyText(JsonNode payload) {
        String body = payload.path("body").path("data").asText("");
        if (!body.isBlank()) {
            return decodeBase64Url(body);
        }
        JsonNode parts = payload.path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if ("text/plain".equalsIgnoreCase(part.path("mimeType").asText())) {
                    String partBody = part.path("body").path("data").asText("");
                    if (!partBody.isBlank()) {
                        return decodeBase64Url(partBody);
                    }
                }
            }
        }
        return "";
    }

    private String extractBodyHtml(JsonNode payload) {
        JsonNode parts = payload.path("parts");
        if (parts.isArray()) {
            for (JsonNode part : parts) {
                if ("text/html".equalsIgnoreCase(part.path("mimeType").asText())) {
                    String partBody = part.path("body").path("data").asText("");
                    if (!partBody.isBlank()) {
                        return decodeBase64Url(partBody);
                    }
                }
            }
        }
        return "";
    }

    private String decodeBase64Url(String base64Url) {
        return new String(java.util.Base64.getUrlDecoder().decode(base64Url), java.nio.charset.StandardCharsets.UTF_8);
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    public record OAuthTokenResponse(String access_token, String refresh_token, Long expires_in, String token_type,
            String scope) {
    }

    public record ThreadPage(List<GmailThreadSnapshot> threads, String nextPageToken) {
    }

    public record HistoryPage(List<String> threadIds, String nextPageToken, String latestHistoryId) {
    }
}
