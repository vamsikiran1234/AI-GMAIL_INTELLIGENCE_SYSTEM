package com.repeatless.gmailintelligence.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persistence for chat_conversation and chat_message.
 *
 * Uses NamedParameterJdbcTemplate (named :param placeholders) throughout —
 * consistent with GmailDataStore and required for compatibility with the
 * Supabase session pooler (prepareThreshold=0).
 *
 * Instants are converted to java.sql.Timestamp before binding because the
 * PostgreSQL JDBC driver does not accept java.time.Instant directly via
 * setObject() in all versions.
 */
@Repository
public class ConversationRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public ConversationRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String createConversation(String userId, String title) {
        String conversationId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update(
                "insert into chat_conversation(id, user_id, title, created_at, updated_at)" +
                " values (:id, :userId, :title, :createdAt, :updatedAt)",
                Map.of(
                        "id",        conversationId,
                        "userId",    userId,
                        "title",     title,
                        "createdAt", Timestamp.from(now),
                        "updatedAt", Timestamp.from(now)));
        return conversationId;
    }

    public void saveMessage(String conversationId, String role, String content, String citationsJson) {
        jdbc.update(
                "insert into chat_message(id, conversation_id, role, content, citations_json, created_at)" +
                " values (:id, :conversationId, :role, :content, cast(:citations as jsonb), :createdAt)",
                Map.of(
                        "id",             UUID.randomUUID().toString(),
                        "conversationId", conversationId,
                        "role",           role,
                        "content",        content,
                        "citations",      citationsJson == null ? "[]" : citationsJson,
                        "createdAt",      Timestamp.from(Instant.now())));
    }

    public List<ChatHistoryItem> listMessages(String conversationId) {
        return jdbc.query(
                "select role, content, created_at" +
                " from chat_message" +
                " where conversation_id = :conversationId" +
                " order by created_at asc",
                Map.of("conversationId", conversationId),
                (rs, rowNum) -> new ChatHistoryItem(
                        rs.getString("role"),
                        rs.getString("content"),
                        rs.getTimestamp("created_at").toInstant()));
    }

    public record ChatHistoryItem(String role, String content, Instant createdAt) {}
}
