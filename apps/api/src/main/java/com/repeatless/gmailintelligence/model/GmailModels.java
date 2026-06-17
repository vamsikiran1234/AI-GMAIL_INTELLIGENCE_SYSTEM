package com.repeatless.gmailintelligence.model;

import java.time.Instant;
import java.util.List;

public final class GmailModels {

    private GmailModels() {
    }

    public record GmailThreadSnapshot(
            String threadId,
            String historyId,
            String subject,
            String labelIds,
            Instant updatedAt,
            List<GmailMessageSnapshot> messages
    ) {
    }

    public record GmailMessageSnapshot(
            String messageId,
            String threadId,
            String messageIdHeader,
            String inReplyTo,
            String references,
            String fromAddress,
            String toAddresses,
            String ccAddresses,
            String subject,
            Instant sentAt,
            String bodyText,
            String bodyHtml,
            long internalDateEpochMillis
    ) {
    }

    public record RetrievalHit(
            String sourceType,
            String sourceId,
            String threadId,
            String sender,
            Instant sentAt,
            String snippet,
            double score
    ) {
    }

    public record DraftEmail(
            String subject,
            String body,
            String threadId,
            String inReplyTo,
            String references
    ) {
    }
}
