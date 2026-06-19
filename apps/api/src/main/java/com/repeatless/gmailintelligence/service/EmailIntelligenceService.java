package com.repeatless.gmailintelligence.service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.repeatless.gmailintelligence.dto.ApiDtos.ChatRequest;
import com.repeatless.gmailintelligence.dto.ApiDtos.ChatResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.DraftRequest;
import com.repeatless.gmailintelligence.dto.ApiDtos.DraftResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.EmailSummaryResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.NewsletterDigestResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.NewsletterItemResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.SourceCitation;
import com.repeatless.gmailintelligence.dto.ApiDtos.SyncStatusResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.ThreadSummaryResponse;
import com.repeatless.gmailintelligence.model.GmailModels.GmailMessageSnapshot;
import com.repeatless.gmailintelligence.model.GmailModels.GmailThreadSnapshot;
import com.repeatless.gmailintelligence.repository.ConversationRepository;

@Service
public class EmailIntelligenceService {

    private final GmailOAuthService gmailOAuthService;
    private final GmailApiClient gmailApiClient;
    private final GmailDataStore gmailDataStore;
    private final AiOrchestratorService aiOrchestratorService;
    private final ConversationRepository conversationRepository;

    public EmailIntelligenceService(GmailOAuthService gmailOAuthService, GmailApiClient gmailApiClient,
            GmailDataStore gmailDataStore, AiOrchestratorService aiOrchestratorService,
            ConversationRepository conversationRepository) {
        this.gmailOAuthService = gmailOAuthService;
        this.gmailApiClient = gmailApiClient;
        this.gmailDataStore = gmailDataStore;
        this.aiOrchestratorService = aiOrchestratorService;
        this.conversationRepository = conversationRepository;
    }

    public SyncStatusResponse syncMailbox(String userId) {
        String accessToken = gmailOAuthService.resolveAccessToken(userId);
        GmailDataStore.SyncCursorRecord cursor = gmailDataStore.findSyncCursor(userId).orElse(null);

        long syncedThreads = 0;
        long syncedMessages = 0;
        String latestHistoryId = cursor == null ? null : cursor.lastHistoryId();

        if (cursor == null || cursor.lastHistoryId() == null || cursor.lastHistoryId().isBlank()) {
            String nextPageToken = null;
            do {
                GmailApiClient.ThreadPage page = gmailApiClient.listThreadsPage(accessToken, nextPageToken, 100);
                for (GmailThreadSnapshot threadSnapshot : page.threads()) {
                    persistThread(userId, threadSnapshot);
                    syncedThreads++;
                    syncedMessages += threadSnapshot.messages().size();
                    latestHistoryId = threadSnapshot.historyId();
                }
                nextPageToken = page.nextPageToken();
            } while (nextPageToken != null && !nextPageToken.isBlank());
            gmailDataStore.saveSyncCursor(userId, latestHistoryId, Instant.now(), "initial");
        } else {
            String nextPageToken = null;
            Map<String, String> changedThreadIds = new LinkedHashMap<>();
            do {
                GmailApiClient.HistoryPage historyPage = gmailApiClient.listThreadIdsFromHistory(accessToken, cursor.lastHistoryId(), nextPageToken);
                for (String threadId : historyPage.threadIds()) {
                    changedThreadIds.put(threadId, threadId);
                }
                latestHistoryId = historyPage.latestHistoryId();
                nextPageToken = historyPage.nextPageToken();
            } while (nextPageToken != null && !nextPageToken.isBlank());

            for (String threadId : changedThreadIds.keySet()) {
                GmailThreadSnapshot threadSnapshot = gmailApiClient.fetchThread(accessToken, threadId);
                persistThread(userId, threadSnapshot);
                syncedThreads++;
                syncedMessages += threadSnapshot.messages().size();
            }
            gmailDataStore.saveSyncCursor(userId, latestHistoryId, Instant.now(), "incremental");
        }

        GmailDataStore.GmailConnectionRecord activeConnection = gmailOAuthService.requireActiveConnection(userId);
        gmailDataStore.saveConnection(userId,
            activeConnection.emailAddress(),
            activeConnection.encryptedRefreshToken(),
            accessToken,
            Instant.now().plusSeconds(3600),
            latestHistoryId);
        return new SyncStatusResponse("completed", Instant.now(), syncedThreads, syncedMessages);
    }

    public ThreadSummaryResponse summarizeThread(String userId, String threadId) {
        GmailDataStore.SyncCursorRecord cursor = gmailDataStore.findSyncCursor(userId).orElse(null);
        if (cursor == null) {
            throw new IllegalStateException("Thread summaries require synced mailbox data");
        }
        List<GmailMessageSnapshot> messages = gmailDataStore.loadThreadMessages(threadId);
        String transcript = buildTranscript(messages);
        String summary = aiOrchestratorService.summarizeThread(transcript);
        List<SourceCitation> citations = buildCitations(messages);
        return new ThreadSummaryResponse(threadId, summary, citations);
    }

    public EmailSummaryResponse summarizeEmail(String userId, String messageId) {
        GmailMessageSnapshot message = gmailDataStore.findMessageById(userId, messageId)
            .orElseThrow(() -> new IllegalStateException("Message not found for summary"));
        if (message.bodyText() == null || message.bodyText().isBlank()) {
            throw new IllegalStateException("Message not found for summary");
        }
        String summary = aiOrchestratorService.generateWithFallback(
                "Summarize the email in one short paragraph without adding new facts.",
                message.bodyText());
        return new EmailSummaryResponse(message.messageId(), summary, List.of(new SourceCitation(
                "message", message.messageId(), message.fromAddress(), message.sentAt(), truncate(message.bodyText()))));
    }

    public DraftResponse createDraft(DraftRequest request) {
        String threadContext = request.threadId() == null || request.threadId().isBlank()
                ? ""
                : buildTranscript(gmailDataStore.loadThreadMessages(request.threadId()));
        String draftBody = aiOrchestratorService.draftEmail(request.prompt(), threadContext, request.mode());
        String subject = request.mode() != null && request.mode().equalsIgnoreCase("reply")
                ? "Re: email thread"
                : "Draft email";
        String draftId = UUID.randomUUID().toString();
        return new DraftResponse(draftId, subject, draftBody, citationsFromTranscript(threadContext));
    }

    public ChatResponse answerQuestion(ChatRequest request) {
        List<Double> embedding = aiOrchestratorService.embed(request.message());
        List<com.repeatless.gmailintelligence.model.GmailModels.RetrievalHit> hits = gmailDataStore.searchRelevantContent(
                request.userId(), embedding, 6);
        String evidence = buildEvidenceBundle(hits);

        String conversationId = request.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = conversationRepository.createConversation(request.userId(), "Email assistant chat");
        }
        conversationRepository.saveMessage(conversationId, "user", request.message(), "[]");

        String answer = aiOrchestratorService.answerQuestion(request.message(), evidence);
        String citationsJson = citationsJson(hits);
        conversationRepository.saveMessage(conversationId, "assistant", answer, citationsJson);
        return new ChatResponse(conversationId, answer, toSourceCitations(hits));
    }

    public NewsletterDigestResponse buildNewsletterDigest(String userId, int days) {
        List<GmailDataStore.ThreadDigestRow> recentThreads = gmailDataStore.listRecentThreads(userId, 100);
        List<NewsletterCandidate> candidates = new ArrayList<>();
        List<SourceCitation> citations = new ArrayList<>();
        LocalDate cutoff = LocalDate.now().minusDays(days);
        for (GmailDataStore.ThreadDigestRow row : recentThreads) {
            if (row.latestMessageAt() != null && row.latestMessageAt().atZone(java.time.ZoneId.systemDefault()).toLocalDate().isBefore(cutoff)) {
                continue;
            }
            if (row.category() != null && row.category().equalsIgnoreCase("NEWSLETTERS")) {
                citations.add(new SourceCitation("thread", row.threadId(), "newsletter", row.latestMessageAt(), row.summary()));
                candidates.add(new NewsletterCandidate(row.subject(), row.summary(), "gmail://thread/" + row.threadId(), row.threadId()));
            }
        }
        return new NewsletterDigestResponse(deduplicateNewsletterCandidates(candidates), citations);
    }

    private void persistThread(String userId, GmailThreadSnapshot threadSnapshot) {
        String transcript = buildTranscript(threadSnapshot.messages());
        String summary = aiOrchestratorService.summarizeThread(transcript);
        String category = normalizeCategory(aiOrchestratorService.categorizeEmail(transcript));
        gmailDataStore.saveThread(userId, threadSnapshot, summary, category);

        for (GmailMessageSnapshot messageSnapshot : threadSnapshot.messages()) {
            String messageSummary = aiOrchestratorService.generateWithFallback(
                    "Summarize this email message in one sentence. Keep it factual.",
                    messageSnapshot.bodyText());
            String messageCategory = normalizeCategory(aiOrchestratorService.categorizeEmail(messageSnapshot.bodyText()));
            gmailDataStore.saveMessage(userId, messageSnapshot, truncate(messageSnapshot.bodyText()), messageSummary, messageCategory);
            List<Double> messageEmbedding = aiOrchestratorService.embed(messageSummary + "\n" + messageSnapshot.bodyText());
            gmailDataStore.saveEmbedding(userId, "message", messageSnapshot.messageId(), messageSnapshot.threadId(),
                    messageSummary, messageEmbedding, messageSnapshot.fromAddress(), messageSnapshot.sentAt());
        }

        List<Double> threadEmbedding = aiOrchestratorService.embed(summary + "\n" + transcript);
        gmailDataStore.saveEmbedding(userId, "thread", threadSnapshot.threadId(), threadSnapshot.threadId(), summary,
                threadEmbedding, threadSnapshot.messages().isEmpty() ? "" : threadSnapshot.messages().getFirst().fromAddress(),
                threadSnapshot.updatedAt());
    }

    private String buildTranscript(List<GmailMessageSnapshot> messages) {
        StringBuilder transcript = new StringBuilder();
        for (GmailMessageSnapshot message : messages) {
            transcript.append("From: ").append(message.fromAddress()).append('\n');
            transcript.append("Sent: ").append(message.sentAt()).append('\n');
            transcript.append("Subject: ").append(message.subject()).append('\n');
            transcript.append("Body: ").append(message.bodyText()).append('\n');
            transcript.append("---\n");
        }
        return transcript.toString();
    }

    private List<SourceCitation> buildCitations(List<GmailMessageSnapshot> messages) {
        return messages.stream()
                .map(message -> new SourceCitation(
                        "message",
                        message.messageId(),
                        message.fromAddress(),
                        message.sentAt(),
                        truncate(message.bodyText())))
                .toList();
    }

    private List<SourceCitation> citationsFromTranscript(String transcript) {
        return List.of(new SourceCitation("thread", UUID.randomUUID().toString(), "thread-context", Instant.now(), truncate(transcript)));
    }

    private String buildEvidenceBundle(List<com.repeatless.gmailintelligence.model.GmailModels.RetrievalHit> hits) {
        StringBuilder evidence = new StringBuilder();
        for (var hit : hits) {
            evidence.append("Source Type: ").append(hit.sourceType()).append('\n');
            evidence.append("Source Id: ").append(hit.sourceId()).append('\n');
            evidence.append("Sender: ").append(hit.sender()).append('\n');
            evidence.append("Sent At: ").append(hit.sentAt()).append('\n');
            evidence.append("Snippet: ").append(hit.snippet()).append('\n');
            evidence.append("---\n");
        }
        return evidence.toString();
    }

    private String citationsJson(List<com.repeatless.gmailintelligence.model.GmailModels.RetrievalHit> hits) {
        return hits.stream()
                .map(hit -> "{\"sourceType\":\"" + hit.sourceType() + "\",\"sourceId\":\"" + hit.sourceId()
                        + "\",\"sender\":\"" + escapeJson(hit.sender()) + "\",\"snippet\":\"" + escapeJson(hit.snippet()) + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private List<SourceCitation> toSourceCitations(List<com.repeatless.gmailintelligence.model.GmailModels.RetrievalHit> hits) {
        return hits.stream()
                .map(hit -> new SourceCitation(hit.sourceType(), hit.sourceId(), hit.sender(), hit.sentAt(), hit.snippet()))
                .toList();
    }

    private String normalizeCategory(String category) {
        String normalized = category == null ? "PERSONAL" : category.toUpperCase();
        if (normalized.contains("NEWSLETTER")) {
            return "NEWSLETTERS";
        }
        if (normalized.contains("JOB")) {
            return "JOB / RECRUITMENT";
        }
        if (normalized.contains("FINANCE")) {
            return "FINANCE";
        }
        if (normalized.contains("NOTIFICATION")) {
            return "NOTIFICATIONS";
        }
        if (normalized.contains("WORK")) {
            return "WORK / PROFESSIONAL";
        }
        return "PERSONAL";
    }

    private String truncate(String content) {
        if (content == null) {
            return "";
        }
        return content.length() <= 240 ? content : content.substring(0, 240);
    }

    private List<NewsletterItemResponse> deduplicateNewsletterCandidates(List<NewsletterCandidate> candidates) {
        List<NewsletterCluster> clusters = new ArrayList<>();
        for (NewsletterCandidate candidate : candidates) {
            List<Double> candidateEmbedding = aiOrchestratorService.embed(candidate.title() + "\n" + candidate.summary());
            NewsletterCluster matchedCluster = null;
            double matchedScore = 0.0;
            for (NewsletterCluster cluster : clusters) {
                double score = cosineSimilarity(candidateEmbedding, cluster.embedding());
                if (score >= 0.90 && score > matchedScore) {
                    matchedCluster = cluster;
                    matchedScore = score;
                }
            }
            if (matchedCluster == null) {
                clusters.add(new NewsletterCluster(candidateEmbedding, candidate.title(), candidate.summary(), new ArrayList<>(List.of(candidate.source())), candidate.source()));
            } else {
                matchedCluster.sources().add(candidate.source());
            }
        }

        return clusters.stream()
                .map(cluster -> new NewsletterItemResponse(
                        cluster.title(),
                        String.join(", ", cluster.sources()),
                        cluster.summary(),
                        cluster.canonicalUrl()))
                .toList();
    }

    private double cosineSimilarity(List<Double> left, List<Double> right) {
        double dot = 0.0;
        double leftMagnitude = 0.0;
        double rightMagnitude = 0.0;
        int size = Math.min(left.size(), right.size());
        for (int index = 0; index < size; index++) {
            double leftValue = left.get(index);
            double rightValue = right.get(index);
            dot += leftValue * rightValue;
            leftMagnitude += leftValue * leftValue;
            rightMagnitude += rightValue * rightValue;
        }
        if (leftMagnitude == 0.0 || rightMagnitude == 0.0) {
            return 0.0;
        }
        return dot / (Math.sqrt(leftMagnitude) * Math.sqrt(rightMagnitude));
    }

    private String toVectorLiteral(List<Double> embedding) {
        return embedding.stream().map(value -> String.format(java.util.Locale.ROOT, "%f", value)).collect(Collectors.joining(",", "[", "]"));
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record NewsletterCandidate(String title, String summary, String source, String threadId) {
    }

    private record NewsletterCluster(List<Double> embedding, String title, String summary, List<String> sources,
            String canonicalUrl) {
    }
}
