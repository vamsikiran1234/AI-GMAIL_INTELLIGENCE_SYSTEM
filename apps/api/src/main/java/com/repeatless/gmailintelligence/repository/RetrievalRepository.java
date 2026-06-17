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

    public void upsertEmbedding(String userId, String sourceType, String sourceId, String threadId, String embeddingText, String embeddingVector) {
        jdbcTemplate.update(
                """
                        insert into email_embedding(
                            id, user_id, source_type, source_id, gmail_thread_id, embedding_text, embedding, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, cast(? as vector), now(), now())
                        on conflict (user_id, source_type, source_id) do update set
                            gmail_thread_id = excluded.gmail_thread_id,
                            embedding_text = excluded.embedding_text,
                            embedding = excluded.embedding,
                            updated_at = now()
                        """,
                sourceId, userId, sourceType, sourceId, threadId, embeddingText, embeddingVector
        );
    }

    public List<RetrievalHit> findSimilar(String userId, String embeddingVector, int limit) {
        return jdbcTemplate.query(
                """
                        select source_type, source_id, gmail_thread_id, sender, sent_at, snippet, score
                        from (
                            select source_type,
                                   source_id,
                                   gmail_thread_id,
                                   coalesce(sender, '') as sender,
                                   created_at as sent_at,
                                   embedding_text as snippet,
                                   1 - (embedding <=> cast(? as vector)) as score
                            from email_embedding
                            where user_id = ?
                            order by embedding <=> cast(? as vector)
                            limit ?
                        ) ranked
                        """,
                (resultSet, rowNum) -> new RetrievalHit(
                        resultSet.getString("source_type"),
                        resultSet.getString("source_id"),
                        resultSet.getString("gmail_thread_id"),
                        resultSet.getString("sender"),
                        resultSet.getObject("sent_at", Instant.class),
                        resultSet.getString("snippet"),
                        resultSet.getDouble("score")
                ),
                embeddingVector, userId, embeddingVector, limit
        );
    }
}
