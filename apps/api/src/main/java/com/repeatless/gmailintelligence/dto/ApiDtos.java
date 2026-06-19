package com.repeatless.gmailintelligence.dto;

import java.time.Instant;
import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public final class ApiDtos {

    private ApiDtos() {
    }

    public record OAuthStartResponse(String authorizationUrl) {
    }

    public record OAuthCallbackResponse(String accountId, String emailAddress) {
    }

    public record SyncRequest(@NotBlank String userId) {
    }

    public record SyncStatusResponse(String status, Instant lastSyncedAt, long syncedThreads, long syncedMessages) {
    }

    public record ThreadSummaryResponse(String threadId, String summary, List<SourceCitation> citations) {
    }

    public record EmailSummaryResponse(String messageId, String summary, List<SourceCitation> citations) {
    }

    public record DraftRequest(@NotBlank String userId, @NotBlank String mode, String threadId, @NotBlank String prompt) {
    }

    public record DraftResponse(String draftId, String subject, String body, List<SourceCitation> citations) {
    }

    public record ChatRequest(@NotBlank String userId, String conversationId, @NotBlank String message) {
    }

    public record ChatResponse(String conversationId, String answer, List<SourceCitation> citations) {
    }

    public record NewsletterDigestRequest(@NotBlank String userId, @Min(1) @Max(30) int days) {
    }

    public record NewsletterDigestResponse(List<NewsletterItemResponse> items, List<SourceCitation> citations) {
    }

    public record NewsletterItemResponse(String title, String source, String summary, String canonicalUrl) {
    }

    public record SourceCitation(String sourceType, String sourceId, String sender, Instant sentAt, String snippet) {
    }

    public record ThreadListResponse(List<ThreadItem> threads, long total, int page, int pageSize) {
    }

    public record ThreadItem(String threadId, String subject, String category, String summary,
            Instant lastMessageAt, int messageCount) {
    }

    public record ThreadMessagesResponse(String threadId, List<MessageItem> messages) {
    }

    public record MessageItem(String messageId, String threadId, String fromAddress, String subject,
            Instant sentAt, String snippet, String summary, String category) {
    }

    public record SendRequest(@NotBlank String userId, @NotBlank String draftId) {
    }

    public record SendResponse(String gmailMessageId, String status) {
    }
}
