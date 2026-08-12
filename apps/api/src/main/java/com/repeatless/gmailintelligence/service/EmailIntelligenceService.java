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
import com.repeatless.gmailintelligence.dto.ApiDtos.MessageItem;
import com.repeatless.gmailintelligence.dto.ApiDtos.NewsletterDigestResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.NewsletterItemResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.SendResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.SourceCitation;
import com.repeatless.gmailintelligence.dto.ApiDtos.SyncStatusResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.ThreadItem;
import com.repeatless.gmailintelligence.dto.ApiDtos.ThreadListResponse;
import com.repeatless.gmailintelligence.dto.ApiDtos.ThreadMessagesResponse;
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
    private final InboxQueryParser inboxQueryParser;
    private final DateRangeResolver dateRangeResolver;
    private final InboxRelevanceRanker inboxRelevanceRanker;

    public EmailIntelligenceService(GmailOAuthService gmailOAuthService, GmailApiClient gmailApiClient,
            GmailDataStore gmailDataStore, AiOrchestratorService aiOrchestratorService,
            ConversationRepository conversationRepository,
            InboxQueryParser inboxQueryParser,
            DateRangeResolver dateRangeResolver,
            InboxRelevanceRanker inboxRelevanceRanker) {
        this.gmailOAuthService = gmailOAuthService;
        this.gmailApiClient = gmailApiClient;
        this.gmailDataStore = gmailDataStore;
        this.aiOrchestratorService = aiOrchestratorService;
        this.conversationRepository = conversationRepository;
        this.inboxQueryParser = inboxQueryParser;
        this.dateRangeResolver = dateRangeResolver;
        this.inboxRelevanceRanker = inboxRelevanceRanker;
    }

    public ThreadListResponse listThreads(String userId, int page, int pageSize) {
        int offset = page * pageSize;
        List<GmailDataStore.ThreadListItem> rows = gmailDataStore.listThreadsPaged(userId, pageSize, offset);
        long total = gmailDataStore.countThreads(userId);
        List<ThreadItem> items = rows.stream()
                .map(r -> new ThreadItem(r.threadId(), r.subject(), r.category(), r.summary(), r.lastMessageAt(), r.messageCount()))
                .toList();
        return new ThreadListResponse(items, total, page, pageSize);
    }

    public ThreadMessagesResponse listThreadMessages(String userId, String threadId) {
        List<GmailDataStore.MessageListItem> rows = gmailDataStore.listMessagesForThread(userId, threadId);
        List<MessageItem> items = rows.stream()
                .map(r -> new MessageItem(r.messageId(), r.threadId(), r.fromAddress(), r.subject(), r.sentAt(), r.snippet(), r.summary(), r.category()))
                .toList();
        return new ThreadMessagesResponse(threadId, items);
    }

    public SendResponse sendDraft(String userId, String draftId) {
        GmailDataStore.DraftRecord draft = gmailDataStore.findDraftById(userId, draftId)
                .orElseThrow(() -> new IllegalStateException("Draft not found: " + draftId));
        String accessToken = gmailOAuthService.resolveAccessToken(userId);
        String senderEmail = gmailDataStore.findConnection(userId)
                .map(GmailDataStore.GmailConnectionRecord::emailAddress)
                .orElseThrow(() -> new IllegalStateException("No Gmail connection for user " + userId));
        String raw = gmailApiClient.buildRawReply(senderEmail, draft.toAddress(), draft.subject(), draft.body(), draft.inReplyTo(), draft.references());
        String gmailMessageId = gmailApiClient.sendMessage(accessToken, raw);
        gmailDataStore.markDraftSent(userId, draftId, gmailMessageId);
        return new SendResponse(gmailMessageId, "sent");
    }

    /**
     * Syncs the mailbox for the given user.
     *
     * Fixes applied here:
     * 1. 401 auto-recovery — if any Gmail API call returns 401 (stale token), the
     *    access token is force-refreshed exactly once and the entire sync is retried.
     *    This handles the common case where the stored access token has expired but
     *    the recorded expiry in the DB is stale/wrong.
     * 2. Initial-sync page cap — the first sync fetches at most {@code INITIAL_SYNC_PAGE_LIMIT}
     *    pages of {@code INITIAL_SYNC_PAGE_SIZE} threads. This keeps the HTTP request
     *    well within the browser's default timeout. Subsequent incremental syncs are
     *    always fast because they only process threads that changed since the last
     *    historyId checkpoint.
     */
    public SyncStatusResponse syncMailbox(String userId) {
        String accessToken = gmailOAuthService.resolveAccessToken(userId);
        try {
            return doSync(userId, accessToken, false);
        } catch (Exception ex) {
            if (GmailApiClient.isAuthError(ex)) {
                // Stored token was invalid — force a refresh and retry once
                System.out.println("[EmailIntelligenceService] 401 on sync, force-refreshing token for user " + userId);
                String freshToken = gmailOAuthService.forceRefreshAccessToken(userId);
                return doSync(userId, freshToken, false);
            }
            throw ex;
        }
    }

    // ── sync implementation ───────────────────────────────────────────────────

    /**
     * Maximum number of pages fetched on the very first (initial) sync.
     * Each page is {@code INITIAL_SYNC_PAGE_SIZE} threads.
     * 3 pages × 20 threads = up to 60 threads on the first call, which completes
     * well within 30 seconds for any inbox.
     */
    private static final int INITIAL_SYNC_PAGE_SIZE  = 20;
    private static final int INITIAL_SYNC_PAGE_LIMIT = 3;

    private SyncStatusResponse doSync(String userId, String accessToken, boolean isRetry) {
        GmailDataStore.SyncCursorRecord cursor = gmailDataStore.findSyncCursor(userId).orElse(null);

        long syncedThreads  = 0;
        long syncedMessages = 0;
        String latestHistoryId = cursor == null ? null : cursor.lastHistoryId();

        if (cursor == null || cursor.lastHistoryId() == null || cursor.lastHistoryId().isBlank()) {
            // ── Initial sync: paginate inbox, cap at INITIAL_SYNC_PAGE_LIMIT pages ──
            String nextPageToken = null;
            int pagesProcessed = 0;
            do {
                GmailApiClient.ThreadPage page =
                        gmailApiClient.listThreadsPage(accessToken, nextPageToken, INITIAL_SYNC_PAGE_SIZE);
                for (GmailThreadSnapshot threadSnapshot : page.threads()) {
                    persistThread(userId, threadSnapshot);
                    syncedThreads++;
                    syncedMessages += threadSnapshot.messages().size();
                    if (threadSnapshot.historyId() != null && !threadSnapshot.historyId().isBlank()) {
                        latestHistoryId = threadSnapshot.historyId();
                    }
                }
                nextPageToken = page.nextPageToken();
                pagesProcessed++;
            } while (nextPageToken != null && !nextPageToken.isBlank()
                    && pagesProcessed < INITIAL_SYNC_PAGE_LIMIT);

            // Save cursor even if we stopped early — the next sync will be incremental
            gmailDataStore.saveSyncCursor(userId, latestHistoryId, Instant.now(), "initial");

        } else {
            // ── Incremental sync: only fetch threads changed since last historyId ──
            String nextPageToken = null;
            Map<String, String> changedThreadIds = new LinkedHashMap<>();
            do {
                GmailApiClient.HistoryPage historyPage =
                        gmailApiClient.listThreadIdsFromHistory(accessToken, cursor.lastHistoryId(), nextPageToken);
                for (String threadId : historyPage.threadIds()) {
                    changedThreadIds.put(threadId, threadId);
                }
                latestHistoryId = historyPage.latestHistoryId();
                nextPageToken   = historyPage.nextPageToken();
            } while (nextPageToken != null && !nextPageToken.isBlank());

            for (String threadId : changedThreadIds.keySet()) {
                // Use fetchThreadOrFallback so a deleted/trashed thread (404) does not
                // abort the entire incremental sync — it is simply skipped gracefully.
                GmailThreadSnapshot threadSnapshot = gmailApiClient.fetchThreadOrFallback(accessToken, threadId);
                if (threadSnapshot.messages().isEmpty() && threadSnapshot.historyId().isBlank()) {
                    // Fallback skeleton — thread was deleted/trashed, nothing to persist
                    continue;
                }
                persistThread(userId, threadSnapshot);
                syncedThreads++;
                syncedMessages += threadSnapshot.messages().size();
            }
            gmailDataStore.saveSyncCursor(userId, latestHistoryId, Instant.now(), "incremental");
        }

        // Persist the fresh/confirmed access token back to the connection record
        GmailDataStore.GmailConnectionRecord activeConnection = gmailOAuthService.requireActiveConnection(userId);
        gmailDataStore.saveConnection(
                userId,
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
        String threadContext = "";
        String inReplyTo = "";
        String references = "";
        String toAddress = "";
        if (request.threadId() != null && !request.threadId().isBlank()) {
            List<GmailMessageSnapshot> messages = gmailDataStore.loadThreadMessages(request.threadId());
            threadContext = buildTranscript(messages);
            if (!messages.isEmpty()) {
                GmailMessageSnapshot lastMsg = messages.getLast();
                inReplyTo = lastMsg.messageIdHeader() == null ? "" : lastMsg.messageIdHeader();
                references = lastMsg.references() == null ? "" : lastMsg.references();
                toAddress = lastMsg.fromAddress() == null ? "" : lastMsg.fromAddress();
            }
        }
        String draftBody = aiOrchestratorService.draftEmail(request.prompt(), threadContext, request.mode());
        String subject = request.mode() != null && request.mode().equalsIgnoreCase("reply") ? "Re: email thread" : "Draft email";
        String draftId = UUID.randomUUID().toString();
        gmailDataStore.saveDraft(draftId, request.userId(), request.threadId(), request.mode(), subject, draftBody, toAddress, inReplyTo, references);
        return new DraftResponse(draftId, subject, draftBody, citationsFromTranscript(threadContext));
    }

    public ChatResponse answerQuestion(ChatRequest request) {
        // ── 1. Parse intent, date, keywords from natural language ─────────────
        InboxQueryParser.InboxQuery query = inboxQueryParser.parse(request.message());

        // ── 2. Resolve date range (null token → last 30 days default) ─────────
        DateRangeResolver.DateRange dateRange = dateRangeResolver.resolve(query.dateToken());

        // ── 3. Map intent to DB category filter ───────────────────────────────
        String categoryFilter = intentToCategoryFilter(query.intent());

        // ── 4. Structured inbox search ─────────────────────────────────────────
        // Fetch up to 40 raw candidates; ranker will trim to the best 15.
        List<GmailDataStore.InboxSearchHit> rawHits = gmailDataStore.searchInbox(
                request.userId(),
                dateRange.from(),
                dateRange.to(),
                categoryFilter,
                query.sender(),
                query.keywords(),
                40);

        // ── 5. Rank + filter by relevance ──────────────────────────────────────
        List<InboxRelevanceRanker.RankedHit> ranked =
                inboxRelevanceRanker.rank(rawHits, query, 15);

        // ── 6. Build evidence bundle from ranked hits ──────────────────────────
        String evidence = buildInboxEvidenceBundle(ranked, dateRange, query);

        // ── 7. Persist conversation ────────────────────────────────────────────
        String conversationId = request.conversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = conversationRepository.createConversation(
                    request.userId(), "Email assistant chat");
        }
        conversationRepository.saveMessage(conversationId, "user", request.message(), "[]");

        // ── 8. Generate answer ─────────────────────────────────────────────────
        String answer = aiOrchestratorService.answerFromInboxSearch(
                request.message(), evidence, ranked.size(), dateRange.label());

        // ── 9. Build citations from ranked hits ────────────────────────────────
        List<SourceCitation> citations = ranked.stream()
                .map(rh -> new SourceCitation(
                        "message",
                        rh.hit().messageId(),
                        rh.hit().fromAddress(),
                        rh.hit().sentAt(),
                        truncate(rh.hit().snippet() != null
                                ? rh.hit().snippet()
                                : rh.hit().subject())))
                .toList();

        String citationsJson = buildCitationsJson(citations);
        conversationRepository.saveMessage(conversationId, "assistant", answer, citationsJson);

        // ── 10. If structured search returned nothing, fall back to semantic RAG ─
        if (ranked.isEmpty()) {
            return fallbackSemanticAnswer(request, conversationId, answer);
        }

        return new ChatResponse(conversationId, answer, citations);
    }

    /**
     * Semantic RAG fallback — used when the structured inbox search returns no results.
     * Embeds the question and searches pgvector, then generates an answer from
     * whatever evidence exists. This handles open-ended questions that aren't
     * inbox-search requests (e.g. "What was agreed in the project meeting?").
     */
    private ChatResponse fallbackSemanticAnswer(ChatRequest request, String conversationId,
            String noResultsAnswer) {
        try {
            List<Double> embedding = aiOrchestratorService.embed(request.message());
            List<com.repeatless.gmailintelligence.model.GmailModels.RetrievalHit> hits =
                    gmailDataStore.searchRelevantContent(request.userId(), embedding, 6);

            if (hits.isEmpty()) {
                // Nothing in semantic index either — return the no-results message
                return new ChatResponse(conversationId, noResultsAnswer, List.of());
            }

            String evidence = buildEvidenceBundle(hits);
            String answer   = aiOrchestratorService.answerQuestion(request.message(), evidence);

            String citationsJson = citationsJson(hits);
            conversationRepository.saveMessage(conversationId, "assistant", answer, citationsJson);

            return new ChatResponse(conversationId, answer, toSourceCitations(hits));
        } catch (Exception e) {
            return new ChatResponse(conversationId, noResultsAnswer, List.of());
        }
    }

    // ─── evidence builders ────────────────────────────────────────────────────

    /**
     * Formats ranked inbox hits into a structured text bundle for the AI.
     * Includes date range context and email count so the model can say
     * "I found N emails from [range]" rather than "no evidence provided".
     */
    private String buildInboxEvidenceBundle(
            List<InboxRelevanceRanker.RankedHit> ranked,
            DateRangeResolver.DateRange dateRange,
            InboxQueryParser.InboxQuery query) {

        StringBuilder sb = new StringBuilder();
        sb.append("Search context:\n");
        sb.append("  Date range: ").append(dateRange.label()).append("\n");
        sb.append("  Intent: ").append(query.intent()).append("\n");
        sb.append("  Results found: ").append(ranked.size()).append("\n\n");

        if (ranked.isEmpty()) {
            sb.append("No matching emails found in the inbox for this search.\n");
            return sb.toString();
        }

        java.time.format.DateTimeFormatter fmt =
            java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy")
                .withZone(java.time.ZoneId.systemDefault());

        for (int i = 0; i < ranked.size(); i++) {
            GmailDataStore.InboxSearchHit hit = ranked.get(i).hit();
            sb.append("--- Email ").append(i + 1).append(" ---\n");
            sb.append("From:    ").append(nullSafe(hit.fromAddress())).append("\n");
            sb.append("Date:    ").append(hit.sentAt() != null ? fmt.format(hit.sentAt()) : "unknown").append("\n");
            sb.append("Subject: ").append(nullSafe(hit.subject())).append("\n");
            sb.append("Category:").append(nullSafe(hit.category())).append("\n");
            // Include the first 400 chars of body so the AI has real content
            String body = hit.bodyText() != null && !hit.bodyText().isBlank()
                    ? hit.bodyText() : hit.snippet();
            if (body != null && !body.isBlank()) {
                sb.append("Content: ").append(body.length() > 400
                        ? body.substring(0, 400) + "…" : body).append("\n");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private String buildCitationsJson(List<SourceCitation> citations) {
        return citations.stream()
                .map(c -> "{\"sourceType\":\"" + escapeJson(c.sourceType())
                        + "\",\"sourceId\":\"" + escapeJson(c.sourceId())
                        + "\",\"sender\":\"" + escapeJson(c.sender())
                        + "\",\"snippet\":\"" + escapeJson(sanitizeSnippet(c.snippet())) + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String nullSafe(String s) { return s == null ? "" : s; }

    /** Maps detected intent to the category prefix stored in the DB. */
    private String intentToCategoryFilter(InboxQueryParser.Intent intent) {
        return switch (intent) {
            case JOB_SEARCH   -> "JOB";
            case FINANCE      -> "FINANCE";
            case NEWSLETTER   -> "NEWSLETTER";
            case NOTIFICATION -> "NOTIFICATION";
            case WORK         -> "WORK";
            case GENERAL      -> null; // no category filter for general queries
        };
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
        String summary = buildLocalThreadSummary(threadSnapshot, transcript);
        String category = inferLocalCategory(threadSnapshot.subject(), transcript);
        gmailDataStore.saveThread(userId, threadSnapshot, summary, category);

        for (GmailMessageSnapshot messageSnapshot : threadSnapshot.messages()) {
            String messageSummary = buildLocalMessageSummary(messageSnapshot);
            String messageCategory = inferLocalCategory(messageSnapshot.subject(), messageSnapshot.bodyText());
            gmailDataStore.saveMessage(userId, messageSnapshot, truncate(messageSnapshot.bodyText()), messageSummary, messageCategory);
        }

    }

    private String buildLocalThreadSummary(GmailThreadSnapshot threadSnapshot, String transcript) {
        if (threadSnapshot.messages().isEmpty()) {
            return truncate(threadSnapshot.subject());
        }
        String firstMessage = threadSnapshot.messages().getFirst().bodyText();
        String body = (firstMessage == null || firstMessage.isBlank()) ? transcript : firstMessage;
        return truncate((threadSnapshot.subject() == null || threadSnapshot.subject().isBlank() ? "Thread" : threadSnapshot.subject())
                + " - " + body);
    }

    private String buildLocalMessageSummary(GmailMessageSnapshot messageSnapshot) {
        String body = messageSnapshot.bodyText();
        if (body == null || body.isBlank()) {
            body = messageSnapshot.subject();
        }
        return truncate(body);
    }

    private String inferLocalCategory(String subject, String content) {
        String text = ((subject == null ? "" : subject) + " " + (content == null ? "" : content)).toLowerCase();
        if (containsAny(text, "newsletter", "unsubscribe", "digest", "weekly", "roundup")) {
            return "NEWSLETTERS";
        }
        if (containsAny(text, "interview", "resume", "application", "hiring", "recruiter", "candidate")) {
            return "JOB / RECRUITMENT";
        }
        if (containsAny(text, "receipt", "invoice", "payment", "refund", "bank", "card")) {
            return "FINANCE";
        }
        if (containsAny(text, "no-reply", "noreply", "notification", "alert", "security", "verification")) {
            return "NOTIFICATIONS";
        }
        if (containsAny(text, "meeting", "project", "deadline", "team", "client", "proposal")) {
            return "WORK / PROFESSIONAL";
        }
        return "PERSONAL";
    }

    private boolean containsAny(String text, String... keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
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
                .map(hit -> "{\"sourceType\":\"" + escapeJson(hit.sourceType())
                        + "\",\"sourceId\":\"" + escapeJson(hit.sourceId())
                        + "\",\"sender\":\"" + escapeJson(hit.sender())
                        + "\",\"snippet\":\"" + escapeJson(sanitizeSnippet(hit.snippet())) + "\"}")
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

    /**
     * Escapes a string for safe embedding inside a JSON string value.
     * Handles backslash, double-quote, and all control characters (0x00-0x1F)
     * including CR (0x0D), LF (0x0A), and TAB (0x09) which PostgreSQL JSONB
     * rejects when left unescaped.
     */
    private String escapeJson(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\r' -> sb.append("\\r");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                default   -> {
                    if (c < 0x20) {
                        // Other ASCII control characters — escape as JSON unicode sequence
                        sb.append("\\u").append(String.format("%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Strips HTML tags and collapses whitespace from an email snippet/body
     * so it is safe and readable as a citation snippet.
     */
    private String sanitizeSnippet(String raw) {
        if (raw == null || raw.isBlank()) return "";
        // Strip HTML tags
        String plain = raw.replaceAll("<[^>]*>", " ");
        // Collapse all whitespace (including \r, \n, \t) into single spaces
        plain = plain.replaceAll("[\\s\\r\\n\\t]+", " ").strip();
        return plain.length() <= 240 ? plain : plain.substring(0, 240);
    }

    private record NewsletterCandidate(String title, String summary, String source, String threadId) {
    }

    private record NewsletterCluster(List<Double> embedding, String title, String summary, List<String> sources,
            String canonicalUrl) {
    }
}
