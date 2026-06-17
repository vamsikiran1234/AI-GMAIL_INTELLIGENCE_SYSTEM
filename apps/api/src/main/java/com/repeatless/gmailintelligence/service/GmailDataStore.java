package com.repeatless.gmailintelligence.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import com.repeatless.gmailintelligence.dto.ApiDtos.SourceCitation;
import com.repeatless.gmailintelligence.model.GmailModels.GmailMessageSnapshot;
import com.repeatless.gmailintelligence.model.GmailModels.GmailThreadSnapshot;
import com.repeatless.gmailintelligence.model.GmailModels.RetrievalHit;

@Service
public class GmailDataStore {

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public GmailDataStore(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void saveConnection(String userId, String emailAddress, String encryptedRefreshToken, String accessToken,
            Instant accessTokenExpiresAt, String lastHistoryId) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("userId", userId);
        params.put("emailAddress", emailAddress);
        params.put("encryptedRefreshToken", encryptedRefreshToken);
        params.put("accessToken", accessToken);
        params.put("accessTokenExpiresAt", accessTokenExpiresAt);
        params.put("lastHistoryId", lastHistoryId);
        jdbcTemplate.update("""
                insert into gmail_connection (user_id, email_address, encrypted_refresh_token, access_token, access_token_expires_at, last_history_id, created_at, updated_at)
                values (:userId, :emailAddress, :encryptedRefreshToken, :accessToken, :accessTokenExpiresAt, :lastHistoryId, now(), now())
                on conflict (user_id) do update set
                    email_address = excluded.email_address,
                    encrypted_refresh_token = excluded.encrypted_refresh_token,
                    access_token = excluded.access_token,
                    access_token_expires_at = excluded.access_token_expires_at,
                    last_history_id = excluded.last_history_id,
                    updated_at = now()
                """, params);
    }

    public Optional<GmailConnectionRecord> findConnection(String userId) {
        List<GmailConnectionRecord> results = jdbcTemplate.query("""
                select user_id, email_address, encrypted_refresh_token, access_token, access_token_expires_at, last_history_id
                from gmail_connection
                where user_id = :userId
                """, Map.of("userId", userId), (resultSet, rowNum) -> new GmailConnectionRecord(
                resultSet.getString("user_id"),
                resultSet.getString("email_address"),
                resultSet.getString("encrypted_refresh_token"),
                resultSet.getString("access_token"),
                resultSet.getTimestamp("access_token_expires_at") == null ? null : resultSet.getTimestamp("access_token_expires_at").toInstant(),
                resultSet.getString("last_history_id")));
        return results.stream().findFirst();
    }

    public void saveSyncCursor(String userId, String lastHistoryId, Instant lastSyncedAt, String syncMode) {
        jdbcTemplate.update("""
                insert into sync_cursor (user_id, last_history_id, last_synced_at, sync_mode, created_at, updated_at)
                values (:userId, :lastHistoryId, :lastSyncedAt, :syncMode, now(), now())
                on conflict (user_id) do update set
                    last_history_id = excluded.last_history_id,
                    last_synced_at = excluded.last_synced_at,
                    sync_mode = excluded.sync_mode,
                    updated_at = now()
                """, Map.of(
                "userId", userId,
                "lastHistoryId", lastHistoryId,
                "lastSyncedAt", lastSyncedAt,
                "syncMode", syncMode));
    }

    public Optional<SyncCursorRecord> findSyncCursor(String userId) {
        List<SyncCursorRecord> results = jdbcTemplate.query("""
                select user_id, last_history_id, last_synced_at, sync_mode
                from sync_cursor
                where user_id = :userId
                """, Map.of("userId", userId), (resultSet, rowNum) -> new SyncCursorRecord(
                resultSet.getString("user_id"),
                resultSet.getString("last_history_id"),
                resultSet.getTimestamp("last_synced_at") == null ? null : resultSet.getTimestamp("last_synced_at").toInstant(),
                resultSet.getString("sync_mode")));
        return results.stream().findFirst();
    }

    public void saveThread(String userId, GmailThreadSnapshot thread, String subject, String labelIdsJson,
            String category, String summary) {
        jdbcTemplate.update("""
                insert into email_thread (user_id, thread_id, history_id, subject, label_ids_json, category, summary, message_count, latest_message_at, updated_at, created_at)
                values (:userId, :threadId, :historyId, :subject, :labelIdsJson, :category, :summary, :messageCount, :latestMessageAt, now(), now())
                on conflict (user_id, thread_id) do update set
                    history_id = excluded.history_id,
                    subject = excluded.subject,
                    label_ids_json = excluded.label_ids_json,
                    category = excluded.category,
                    summary = excluded.summary,
                    message_count = excluded.message_count,
                    latest_message_at = excluded.latest_message_at,
                    updated_at = now()
                """, Map.of(
                "userId", userId,
                "threadId", thread.threadId(),
                "historyId", thread.historyId(),
                "subject", subject,
                "labelIdsJson", labelIdsJson,
                "category", category,
                "summary", summary,
                "messageCount", thread.messages().size(),
                "latestMessageAt", thread.updatedAt()));
    }

    public void saveMessage(String userId, GmailMessageSnapshot message, String snippet, String summary,
            String category) {
        jdbcTemplate.update("""
                insert into email_message (user_id, thread_id, message_id, message_id_header, in_reply_to, references_header, from_address, to_addresses_json, cc_addresses_json, subject, sent_at, body_text, body_html, snippet, summary, category, raw_internal_date, created_at, updated_at)
                values (:userId, :threadId, :messageId, :messageIdHeader, :inReplyTo, :referencesHeader, :fromAddress, :toAddressesJson, :ccAddressesJson, :subject, :sentAt, :bodyText, :bodyHtml, :snippet, :summary, :category, :rawInternalDate, now(), now())
                on conflict (user_id, message_id) do update set
                    thread_id = excluded.thread_id,
                    message_id_header = excluded.message_id_header,
                    in_reply_to = excluded.in_reply_to,
                    references_header = excluded.references_header,
                    from_address = excluded.from_address,
                    to_addresses_json = excluded.to_addresses_json,
                    cc_addresses_json = excluded.cc_addresses_json,
                    subject = excluded.subject,
                    sent_at = excluded.sent_at,
                    body_text = excluded.body_text,
                    body_html = excluded.body_html,
                    snippet = excluded.snippet,
                    summary = excluded.summary,
                    category = excluded.category,
                    raw_internal_date = excluded.raw_internal_date,
                    updated_at = now()
                """, Map.of(
                "userId", userId,
                "threadId", message.threadId(),
                "messageId", message.messageId(),
                "messageIdHeader", message.messageIdHeader(),
                "inReplyTo", message.inReplyTo(),
                "referencesHeader", message.references(),
                "fromAddress", message.fromAddress(),
                "toAddressesJson", message.toAddresses(),
                "ccAddressesJson", message.ccAddresses(),
                "subject", message.subject(),
                "sentAt", message.sentAt(),
                "bodyText", message.bodyText(),
                "bodyHtml", message.bodyHtml(),
                "snippet", snippet,
                "summary", summary,
                "category", category,
                "rawInternalDate", message.internalDateEpochMillis()));
    }

    public void saveEmbedding(String userId, String sourceType, String sourceId, String threadId, String content,
            List<Double> embedding, String sender, Instant sentAt) {
        jdbcTemplate.update("""
                insert into email_embedding (user_id, source_type, source_id, thread_id, content, embedding, sender, sent_at, created_at, updated_at)
                values (:userId, :sourceType, :sourceId, :threadId, :content, CAST(:embedding AS vector), :sender, :sentAt, now(), now())
                on conflict (user_id, source_type, source_id) do update set
                    thread_id = excluded.thread_id,
                    content = excluded.content,
                    embedding = excluded.embedding,
                    sender = excluded.sender,
                    sent_at = excluded.sent_at,
                    updated_at = now()
                """, Map.of(
                "userId", userId,
                "sourceType", sourceType,
                "sourceId", sourceId,
                "threadId", threadId,
                "content", content,
                "embedding", toVectorLiteral(embedding),
                "sender", sender,
                "sentAt", sentAt));
    }

    public List<RetrievalHit> searchRelevantContent(String userId, List<Double> embedding, int limit) {
        return jdbcTemplate.query("""
                select source_type, source_id, thread_id, sender, sent_at, content,
                       1 - (embedding <=> CAST(:embedding AS vector)) as score
                from email_embedding
                where user_id = :userId
                order by embedding <=> CAST(:embedding AS vector)
                limit :limit
                """, Map.of(
                "userId", userId,
                "embedding", toVectorLiteral(embedding),
                "limit", limit), (resultSet, rowNum) -> new RetrievalHit(
                resultSet.getString("source_type"),
                resultSet.getString("source_id"),
                resultSet.getString("thread_id"),
                resultSet.getString("sender"),
                resultSet.getTimestamp("sent_at") == null ? null : resultSet.getTimestamp("sent_at").toInstant(),
                resultSet.getString("content"),
                resultSet.getDouble("score")));
    }

    public List<ThreadDigestRow> listRecentThreads(String userId, int limit) {
        return jdbcTemplate.query("""
                select thread_id, subject, category, summary, latest_message_at
                from email_thread
                where user_id = :userId
                order by latest_message_at desc nulls last
                limit :limit
                """, Map.of("userId", userId, "limit", limit), (resultSet, rowNum) -> new ThreadDigestRow(
                resultSet.getString("thread_id"),
                resultSet.getString("subject"),
                resultSet.getString("category"),
                resultSet.getString("summary"),
                resultSet.getTimestamp("latest_message_at") == null ? null : resultSet.getTimestamp("latest_message_at").toInstant()));
    }

    public List<GmailMessageSnapshot> loadThreadMessages(String threadId) {
        return jdbcTemplate.query("""
                  select message_id, thread_id, message_id_header, in_reply_to, references_header, from_address,
                      to_addresses, cc_addresses, subject, sent_at, body_text, body_html, raw_internal_date
                from email_message
                where thread_id = :threadId
                order by sent_at asc nulls last, raw_internal_date asc
                """, Map.of("threadId", threadId), (resultSet, rowNum) -> new GmailMessageSnapshot(
                resultSet.getString("message_id"),
                resultSet.getString("thread_id"),
                resultSet.getString("message_id_header"),
                resultSet.getString("in_reply_to"),
                resultSet.getString("references_header"),
                resultSet.getString("from_address"),
                resultSet.getString("to_addresses"),
                resultSet.getString("cc_addresses"),
                resultSet.getString("subject"),
                resultSet.getTimestamp("sent_at") == null ? null : resultSet.getTimestamp("sent_at").toInstant(),
                resultSet.getString("body_text"),
                resultSet.getString("body_html"),
                resultSet.getLong("raw_internal_date")));
    }

    public Optional<GmailMessageSnapshot> findMessageById(String userId, String messageId) {
        List<GmailMessageSnapshot> results = jdbcTemplate.query("""
                  select message_id, thread_id, message_id_header, in_reply_to, references_header, from_address,
                      to_addresses, cc_addresses, subject, sent_at, body_text, body_html, raw_internal_date
                from email_message
                where user_id = :userId and message_id = :messageId
                """, Map.of("userId", userId, "messageId", messageId), (resultSet, rowNum) -> new GmailMessageSnapshot(
                resultSet.getString("message_id"),
                resultSet.getString("thread_id"),
                resultSet.getString("message_id_header"),
                resultSet.getString("in_reply_to"),
                resultSet.getString("references_header"),
                resultSet.getString("from_address"),
                resultSet.getString("to_addresses"),
                resultSet.getString("cc_addresses"),
                resultSet.getString("subject"),
                resultSet.getTimestamp("sent_at") == null ? null : resultSet.getTimestamp("sent_at").toInstant(),
                resultSet.getString("body_text"),
                resultSet.getString("body_html"),
                resultSet.getLong("raw_internal_date")));
        return results.stream().findFirst();
    }

    private String toVectorLiteral(List<Double> embedding) {
        return embedding.stream()
                .map(value -> String.format(java.util.Locale.ROOT, "%f", value))
                .reduce((left, right) -> left + "," + right)
                .map(value -> "[" + value + "]")
                .orElse("[]");
    }

    public record GmailConnectionRecord(
            String userId,
            String emailAddress,
            String encryptedRefreshToken,
            String accessToken,
            Instant accessTokenExpiresAt,
            String lastHistoryId) {
    }

    public record SyncCursorRecord(String userId, String lastHistoryId, Instant lastSyncedAt, String syncMode) {
    }

    public record ThreadDigestRow(String threadId, String subject, String category, String summary, Instant latestMessageAt) {
    }
}
