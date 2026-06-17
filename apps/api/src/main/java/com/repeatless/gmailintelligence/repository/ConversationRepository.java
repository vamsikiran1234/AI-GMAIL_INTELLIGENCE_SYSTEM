package com.repeatless.gmailintelligence.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ConversationRepository {

    private final JdbcTemplate jdbcTemplate;

    public ConversationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String createConversation(String userId, String title) {
        String conversationId = UUID.randomUUID().toString();
        jdbcTemplate.update(
                """
                        insert into chat_conversation(id, user_id, title, created_at, updated_at)
                        values (?, ?, ?, now(), now())
                        """,
                conversationId, userId, title
        );
        return conversationId;
    }

    public void saveMessage(String conversationId, String role, String content, String citationsJson) {
        jdbcTemplate.update(
                """
                        insert into chat_message(id, conversation_id, role, content, citations_json, created_at)
                        values (?, ?, ?, ?, cast(? as jsonb), now())
                        """,
                UUID.randomUUID().toString(), conversationId, role, content, citationsJson
        );
    }

    public List<ChatHistoryItem> listMessages(String conversationId) {
        return jdbcTemplate.query(
                """
                        select role, content, created_at
                        from chat_message
                        where conversation_id = ?
                        order by created_at asc
                        """,
                (resultSet, rowNum) -> new ChatHistoryItem(
                        resultSet.getString("role"),
                        resultSet.getString("content"),
                        resultSet.getObject("created_at", Instant.class)
                ),
                conversationId
        );
    }

    public record ChatHistoryItem(String role, String content, Instant createdAt) {
    }
}
