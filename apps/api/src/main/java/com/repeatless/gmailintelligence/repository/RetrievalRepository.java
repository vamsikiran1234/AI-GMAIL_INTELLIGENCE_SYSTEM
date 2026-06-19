package com.repeatless.gmailintelligence.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.repeatless.gmailintelligence.model.GmailModels.RetrievalHit;

@Repository
public class RetrievalRepository {

    private final JdbcTemplate jdbcTemplate;

    public RetrievalRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsertEmbedding(String userId, String sourceType, String sourceId, String threadId,
            String content, String embeddingVector, String sender, Instant sentAt) {
        jdbcTemplate.update(
                """
                        insert into email_embedding(
                            user_id, source_type, source_id, thread_id, content, embedding,
                            sender, sent_at, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, cast(? as vector), ?, ?, now(), now())
                        on conflict (user_id, source_type, source_id) do update set
                            thread_id = excluded.thread_id,
                            content = excluded.content,
                            embedding = excluded.embedding,
                            sender = excluded.sender,
                            sent_at = excluded.sent_at,
                            updated_at = now()
                        """,
                userId, sourceType, sourceId, threadId, content, embeddingVector, sender, sentAt
        );
    }

    public List<RetrievalHit> findSimilar(String userId, String embeddingVector, int limit) {
        return jdbcTemplate.query(
                """
                        select source_type, source_id, thread_id,
                               coalesce(sender, '') as sender,
                               sent_at,
                               content as snippet,
                               1 - (embedding <=> cast(? as vector)) as score
                        from email_embedding
                        where user_id = ?
                        order by embedding <=> cast(? as vector)
                        limit ?
                        """,
                (resultSet, rowNum) -> new RetrievalHit(
                        resultSet.getString("source_type"),
                        resultSet.getString("source_id"),
                        resultSet.getString("thread_id"),
                        resultSet.getString("sender"),
                        resultSet.getObject("sent_at", Instant.class),
                        resultSet.getString("snippet"),
                        resultSet.getDouble("score")
                ),
                embeddingVector, userId, embeddingVector, limit
        );
    }
}
