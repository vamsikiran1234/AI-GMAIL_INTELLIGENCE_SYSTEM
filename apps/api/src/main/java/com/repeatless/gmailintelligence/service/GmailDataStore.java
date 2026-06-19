package com.repeatless.gmailintelligence.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.repeatless.gmailintelligence.model.GmailModels.GmailMessageSnapshot;
import com.repeatless.gmailintelligence.model.GmailModels.GmailThreadSnapshot;
import com.repeatless.gmailintelligence.model.GmailModels.RetrievalHit;

/**
 * All SQL uses check-then-insert/update pattern instead of ON CONFLICT
 * because Supabase session pooler rejects ON CONFLICT ... DO UPDATE with
 * NamedParameterJdbcTemplate prepared statement parsing.
 * All now() in VALUES replaced with Java Instant.now() parameters.
 */
@Service
public class GmailDataStore {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public GmailDataStore(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ─── app_user ────────────────────────────────────────────────────────────

    public void ensureUser(String userId, String emailAddress, String displayName) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from app_user where id = :userId",
                Map.of("userId", userId), Integer.class);
        if (count != null && count > 0) {
            jdbcTemplate.update(
                    "update app_user set email_address = :emailAddress, updated_at = :now where id = :userId",
                    Map.of("userId", userId, "emailAddress", emailAddress, "now", Instant.now()));
        } else {
            Instant now = Instant.now();
            jdbcTemplate.update(
                    "insert into app_user (id, email_address, display_name, created_at, updated_at) values (:userId, :emailAddress, :displayName, :now, :now)",
                    Map.of("userId", userId, "emailAddress", emailAddress,
                            "displayName", displayName == null ? "" : displayName, "now", now));
        }
    }

    // ─── gmail_connection ─────────────────────────────────────────────────────

    public void saveConnection(String userId, String emailAddress, String encryptedRefreshToken,
            String accessToken, Instant accessTokenExpiresAt, String lastHistoryId) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from gmail_connection where user_id = :userId",
                Map.of("userId", userId), Integer.class);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("userId", userId);
        p.put("emailAddress", emailAddress);
        p.put("encryptedRefreshToken", encryptedRefreshToken);
        p.put("accessToken", accessToken);
        p.put("accessTokenExpiresAt", accessTokenExpiresAt);
        p.put("lastHistoryId", lastHistoryId);
        p.put("now", Instant.now());
        if (count != null && count > 0) {
            jdbcTemplate.update("""
                    update gmail_connection set
                        email_address = :emailAddress,
                        encrypted_refresh_token = :encryptedRefreshToken,
                        access_token = :accessToken,
                        access_token_expires_at = :accessTokenExpiresAt,
                        last_history_id = :lastHistoryId,
                        updated_at = :now
                    where user_id = :userId
                    """, p);
        } else {
            jdbcTemplate.update("""
                    insert into gmail_connection
                        (user_id, email_address, encrypted_refresh_token, access_token,
                         access_token_expires_at, last_history_id, created_at, updated_at)
                    values
                        (:userId, :emailAddress, :encryptedRefreshToken, :accessToken,
                         :accessTokenExpiresAt, :lastHistoryId, :now, :now)
                    """, p);
        }
    }

    public Optional<GmailConnectionRecord> findConnection(String userId) {
        List<GmailConnectionRecord> results = jdbcTemplate.query("""
                select user_id, email_address, encrypted_refresh_token, access_token,
                       access_token_expires_at, last_history_id
                from gmail_connection where user_id = :userId
                """, Map.of("userId", userId), (rs, n) -> new GmailConnectionRecord(
                rs.getString("user_id"), rs.getString("email_address"),
                rs.getString("encrypted_refresh_token"), rs.getString("access_token"),
                rs.getTimestamp("access_token_expires_at") == null ? null
                        : rs.getTimestamp("access_token_expires_at").toInstant(),
                rs.getString("last_history_id")));
        return results.stream().findFirst();
    }

    // ─── sync_cursor ──────────────────────────────────────────────────────────

    public void saveSyncCursor(String userId, String lastHistoryId, Instant lastSyncedAt, String syncMode) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from sync_cursor where user_id = :userId",
                Map.of("userId", userId), Integer.class);
        Map<String, Object> p = Map.of("userId", userId, "lastHistoryId", lastHistoryId,
                "lastSyncedAt", lastSyncedAt, "syncMode", syncMode, "now", Instant.now());
        if (count != null && count > 0) {
            jdbcTemplate.update("""
                    update sync_cursor set last_history_id = :lastHistoryId,
                        last_synced_at = :lastSyncedAt, sync_mode = :syncMode, updated_at = :now
                    where user_id = :userId
                    """, p);
        } else {
            jdbcTemplate.update("""
                    insert into sync_cursor (user_id, last_history_id, last_synced_at, sync_mode, created_at, updated_at)
                    values (:userId, :lastHistoryId, :lastSyncedAt, :syncMode, :now, :now)
                    """, p);
        }
    }

    public Optional<SyncCursorRecord> findSyncCursor(String userId) {
        List<SyncCursorRecord> results = jdbcTemplate.query("""
                select user_id, last_history_id, last_synced_at, sync_mode
                from sync_cursor where user_id = :userId
                """, Map.of("userId", userId), (rs, n) -> new SyncCursorRecord(
                rs.getString("user_id"), rs.getString("last_history_id"),
                rs.getTimestamp("last_synced_at") == null ? null : rs.getTimestamp("last_synced_at").toInstant(),
                rs.getString("sync_mode")));
        return results.stream().findFirst();
    }

    // ─── email_thread ─────────────────────────────────────────────────────────

    public void saveThread(String userId, GmailThreadSnapshot thread, String category, String summary) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from email_thread where user_id = :userId and thread_id = :threadId",
                Map.of("userId", userId, "threadId", thread.threadId()), Integer.class);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("userId", userId);
        p.put("threadId", thread.threadId());
        p.put("historyId", thread.historyId());
        p.put("subject", thread.subject() == null ? "" : thread.subject());
        p.put("labelIdsJson", thread.labelIds() == null ? "[]" : thread.labelIds());
        p.put("category", category);
        p.put("summary", summary);
        p.put("messageCount", thread.messages().size());
        p.put("lastMessageAt", thread.updatedAt());
        p.put("now", Instant.now());
        if (count != null && count > 0) {
            jdbcTemplate.update("""
                    update email_thread set history_id = :historyId, subject = :subject,
                        label_ids_json = :labelIdsJson, category = :category, summary = :summary,
                        message_count = :messageCount, last_message_at = :lastMessageAt,
                        updated_at = :now
                    where user_id = :userId and thread_id = :threadId
                    """, p);
        } else {
            jdbcTemplate.update("""
                    insert into email_thread
                        (user_id, thread_id, history_id, subject, label_ids_json, category, summary,
                         message_count, last_message_at, created_at, updated_at)
                    values
                        (:userId, :threadId, :historyId, :subject, :labelIdsJson, :category, :summary,
                         :messageCount, :lastMessageAt, :now, :now)
                    """, p);
        }
    }

    // ─── email_message ────────────────────────────────────────────────────────

    public void saveMessage(String userId, GmailMessageSnapshot message, String snippet,
            String summary, String category) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from email_message where user_id = :userId and message_id = :messageId",
                Map.of("userId", userId, "messageId", message.messageId()), Integer.class);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("userId", userId);
        p.put("threadId", message.threadId());
        p.put("messageId", message.messageId());
        p.put("messageIdHeader", message.messageIdHeader());
        p.put("inReplyTo", message.inReplyTo());
        p.put("referencesHeader", message.references());
        p.put("fromAddress", message.fromAddress());
        p.put("toAddressesJson", message.toAddresses());
        p.put("ccAddressesJson", message.ccAddresses());
        p.put("subject", message.subject());
        p.put("sentAt", message.sentAt());
        p.put("bodyText", message.bodyText());
        p.put("bodyHtml", message.bodyHtml());
        p.put("snippet", snippet);
        p.put("summary", summary);
        p.put("category", category);
        p.put("rawInternalDate", message.internalDateEpochMillis());
        p.put("now", Instant.now());
        if (count != null && count > 0) {
            jdbcTemplate.update("""
                    update email_message set thread_id=:threadId, message_id_header=:messageIdHeader,
                        in_reply_to=:inReplyTo, references_header=:referencesHeader,
                        from_address=:fromAddress, to_addresses_json=:toAddressesJson,
                        cc_addresses_json=:ccAddressesJson, subject=:subject, sent_at=:sentAt,
                        body_text=:bodyText, body_html=:bodyHtml, snippet=:snippet,
                        summary=:summary, category=:category, raw_internal_date=:rawInternalDate,
                        updated_at=:now
                    where user_id=:userId and message_id=:messageId
                    """, p);
        } else {
            jdbcTemplate.update("""
                    insert into email_message
                        (user_id, thread_id, message_id, message_id_header, in_reply_to,
                         references_header, from_address, to_addresses_json, cc_addresses_json,
                         subject, sent_at, body_text, body_html, snippet, summary, category,
                         raw_internal_date, created_at, updated_at)
                    values
                        (:userId, :threadId, :messageId, :messageIdHeader, :inReplyTo,
                         :referencesHeader, :fromAddress, :toAddressesJson, :ccAddressesJson,
                         :subject, :sentAt, :bodyText, :bodyHtml, :snippet, :summary, :category,
                         :rawInternalDate, :now, :now)
                    """, p);
        }
    }

    // ─── email_embedding ──────────────────────────────────────────────────────

    public void saveEmbedding(String userId, String sourceType, String sourceId, String threadId,
            String content, List<Double> embedding, String sender, Instant sentAt) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from email_embedding where user_id = :userId and source_type = :sourceType and source_id = :sourceId",
                Map.of("userId", userId, "sourceType", sourceType, "sourceId", sourceId), Integer.class);
        Map<String, Object> p = Map.of("userId", userId, "sourceType", sourceType,
                "sourceId", sourceId, "threadId", threadId, "content", content,
                "embedding", toVectorLiteral(embedding), "sender", sender,
                "sentAt", sentAt, "now", Instant.now());
        if (count != null && count > 0) {
            jdbcTemplate.update("""
                    update email_embedding set thread_id=:threadId, content=:content,
                        embedding=CAST(:embedding AS vector), sender=:sender,
                        sent_at=:sentAt, updated_at=:now
                    where user_id=:userId and source_type=:sourceType and source_id=:sourceId
                    """, p);
        } else {
            jdbcTemplate.update("""
                    insert into email_embedding
                        (user_id, source_type, source_id, thread_id, content, embedding,
                         sender, sent_at, created_at, updated_at)
                    values
                        (:userId, :sourceType, :sourceId, :threadId, :content,
                         CAST(:embedding AS vector), :sender, :sentAt, :now, :now)
                    """, p);
        }
    }

    // ─── draft_email ──────────────────────────────────────────────────────────

    public void saveDraft(String draftId, String userId, String gmailThreadId, String mode,
            String subject, String body, String toAddress, String inReplyTo, String references) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from draft_email where id = :id",
                Map.of("id", draftId), Integer.class);
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", draftId);
        p.put("userId", userId);
        p.put("gmailThreadId", gmailThreadId == null ? "" : gmailThreadId);
        p.put("mode", mode);
        p.put("subject", subject);
        p.put("body", body);
        p.put("toAddress", toAddress);
        p.put("inReplyTo", inReplyTo);
        p.put("references", references);
        p.put("now", Instant.now());
        if (count != null && count > 0) {
            jdbcTemplate.update(
                    "update draft_email set body=:body, updated_at=:now where id=:id", p);
        } else {
            jdbcTemplate.update("""
                    insert into draft_email
                        (id, user_id, gmail_thread_id, mode, subject, body, to_address,
                         in_reply_to, references_header, status, citations_json, created_at, updated_at)
                    values
                        (:id, :userId, :gmailThreadId, :mode, :subject, :body, :toAddress,
                         :inReplyTo, :references, 'draft', '[]', :now, :now)
                    """, p);
        }
    }

    // ─── Read methods ─────────────────────────────────────────────────────────

    public List<RetrievalHit> searchRelevantContent(String userId, List<Double> embedding, int limit) {
        return jdbcTemplate.query("""
                select source_type, source_id, thread_id, sender, sent_at, content,
                       1 - (embedding <=> CAST(:embedding AS vector)) as score
                from email_embedding
                where user_id = :userId
                order by embedding <=> CAST(:embedding AS vector)
                limit :limit
                """, Map.of("userId", userId, "embedding", toVectorLiteral(embedding), "limit", limit),
                (rs, n) -> new RetrievalHit(rs.getString("source_type"), rs.getString("source_id"),
                        rs.getString("thread_id"), rs.getString("sender"),
                        rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(),
                        rs.getString("content"), rs.getDouble("score")));
    }

    public List<ThreadDigestRow> listRecentThreads(String userId, int limit) {
        return jdbcTemplate.query("""
                select thread_id, subject, category, summary, last_message_at
                from email_thread where user_id = :userId
                order by last_message_at desc nulls last limit :limit
                """, Map.of("userId", userId, "limit", limit),
                (rs, n) -> new ThreadDigestRow(rs.getString("thread_id"), rs.getString("subject"),
                        rs.getString("category"), rs.getString("summary"),
                        rs.getTimestamp("last_message_at") == null ? null
                                : rs.getTimestamp("last_message_at").toInstant()));
    }

    public List<GmailMessageSnapshot> loadThreadMessages(String threadId) {
        return jdbcTemplate.query("""
                select message_id, thread_id, message_id_header, in_reply_to, references_header,
                       from_address, to_addresses_json, cc_addresses_json, subject, sent_at,
                       body_text, body_html, raw_internal_date
                from email_message where thread_id = :threadId
                order by sent_at asc nulls last, raw_internal_date asc
                """, Map.of("threadId", threadId), this::mapMessage);
    }

    public Optional<GmailMessageSnapshot> findMessageById(String userId, String messageId) {
        return jdbcTemplate.query("""
                select message_id, thread_id, message_id_header, in_reply_to, references_header,
                       from_address, to_addresses_json, cc_addresses_json, subject, sent_at,
                       body_text, body_html, raw_internal_date
                from email_message where user_id = :userId and message_id = :messageId
                """, Map.of("userId", userId, "messageId", messageId), this::mapMessage)
                .stream().findFirst();
    }

    public List<ThreadListItem> listThreadsPaged(String userId, int limit, int offset) {
        return jdbcTemplate.query("""
                select thread_id, subject, category, summary, last_message_at, message_count
                from email_thread where user_id = :userId
                order by last_message_at desc nulls last limit :limit offset :offset
                """, Map.of("userId", userId, "limit", limit, "offset", offset),
                (rs, n) -> new ThreadListItem(rs.getString("thread_id"), rs.getString("subject"),
                        rs.getString("category"), rs.getString("summary"),
                        rs.getTimestamp("last_message_at") == null ? null
                                : rs.getTimestamp("last_message_at").toInstant(),
                        rs.getInt("message_count")));
    }

    public long countThreads(String userId) {
        Long count = jdbcTemplate.queryForObject(
                "select count(*) from email_thread where user_id = :userId",
                Map.of("userId", userId), Long.class);
        return count == null ? 0L : count;
    }

    public List<MessageListItem> listMessagesForThread(String userId, String threadId) {
        return jdbcTemplate.query("""
                select message_id, thread_id, from_address, subject, sent_at,
                       snippet, summary, category
                from email_message where user_id = :userId and thread_id = :threadId
                order by sent_at asc nulls last, raw_internal_date asc
                """, Map.of("userId", userId, "threadId", threadId),
                (rs, n) -> new MessageListItem(rs.getString("message_id"), rs.getString("thread_id"),
                        rs.getString("from_address"), rs.getString("subject"),
                        rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(),
                        rs.getString("snippet"), rs.getString("summary"), rs.getString("category")));
    }

    public Optional<DraftRecord> findDraftById(String userId, String draftId) {
        return jdbcTemplate.query("""
                select id, user_id, gmail_thread_id, mode, subject, body,
                       coalesce(to_address,'') as to_address,
                       coalesce(in_reply_to,'') as in_reply_to,
                       coalesce(references_header,'') as references_header
                from draft_email where user_id=:userId and id=:draftId
                """, Map.of("userId", userId, "draftId", draftId),
                (rs, n) -> new DraftRecord(rs.getString("id"), rs.getString("user_id"),
                        rs.getString("gmail_thread_id"), rs.getString("mode"),
                        rs.getString("subject"), rs.getString("body"),
                        rs.getString("to_address"), rs.getString("in_reply_to"),
                        rs.getString("references_header")))
                .stream().findFirst();
    }

    public void markDraftSent(String userId, String draftId, String gmailMessageId) {
        jdbcTemplate.update("""
                update draft_email set status='sent', gmail_message_id=:gmailMessageId, updated_at=:now
                where user_id=:userId and id=:draftId
                """, Map.of("userId", userId, "draftId", draftId,
                "gmailMessageId", gmailMessageId, "now", Instant.now()));
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private GmailMessageSnapshot mapMessage(ResultSet rs, int n) throws SQLException {
        return new GmailMessageSnapshot(
                rs.getString("message_id"), rs.getString("thread_id"),
                rs.getString("message_id_header"), rs.getString("in_reply_to"),
                rs.getString("references_header"), rs.getString("from_address"),
                rs.getString("to_addresses_json"), rs.getString("cc_addresses_json"),
                rs.getString("subject"),
                rs.getTimestamp("sent_at") == null ? null : rs.getTimestamp("sent_at").toInstant(),
                rs.getString("body_text"), rs.getString("body_html"),
                rs.getLong("raw_internal_date"));
    }

    private String toVectorLiteral(List<Double> embedding) {
        return embedding.stream()
                .map(v -> String.format(java.util.Locale.ROOT, "%f", v))
                .reduce((a, b) -> a + "," + b)
                .map(v -> "[" + v + "]")
                .orElse("[]");
    }

    // ─── Records ─────────────────────────────────────────────────────────────

    public record GmailConnectionRecord(String userId, String emailAddress,
            String encryptedRefreshToken, String accessToken,
            Instant accessTokenExpiresAt, String lastHistoryId) {}

    public record SyncCursorRecord(String userId, String lastHistoryId,
            Instant lastSyncedAt, String syncMode) {}

    public record ThreadDigestRow(String threadId, String subject, String category,
            String summary, Instant latestMessageAt) {}

    public record ThreadListItem(String threadId, String subject, String category,
            String summary, Instant lastMessageAt, int messageCount) {}

    public record MessageListItem(String messageId, String threadId, String fromAddress,
            String subject, Instant sentAt, String snippet, String summary, String category) {}

    public record DraftRecord(String id, String userId, String gmailThreadId, String mode,
            String subject, String body, String toAddress, String inReplyTo, String references) {}
}
