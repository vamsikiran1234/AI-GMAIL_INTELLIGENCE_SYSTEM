package com.repeatless.gmailintelligence.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GmailConnectionRepository {

    private final JdbcTemplate jdbcTemplate;

    public GmailConnectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<GmailConnectionRecord> findByUserId(String userId) {
        return jdbcTemplate.query(
                """
                        select user_id, email_address, access_token, encrypted_refresh_token,
                               access_token_expires_at, last_history_id, last_synced_at,
                               synced_thread_count, synced_message_count
                        from gmail_connection
                        where user_id = ?
                        """,
                this::mapRecord,
                userId
        ).stream().findFirst();
    }

    public void upsert(GmailConnectionRecord record) {
        jdbcTemplate.update(
                """
                        insert into gmail_connection(
                            user_id, email_address, access_token, encrypted_refresh_token,
                            access_token_expires_at, last_history_id, last_synced_at,
                            synced_thread_count, synced_message_count
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?)
                        on conflict (user_id) do update set
                            email_address = excluded.email_address,
                            access_token = excluded.access_token,
                            encrypted_refresh_token = excluded.encrypted_refresh_token,
                            access_token_expires_at = excluded.access_token_expires_at,
                            last_history_id = excluded.last_history_id,
                            last_synced_at = excluded.last_synced_at,
                            synced_thread_count = excluded.synced_thread_count,
                            synced_message_count = excluded.synced_message_count,
                            updated_at = now()
                        """,
                record.userId(), record.emailAddress(), record.accessToken(), record.encryptedRefreshToken(),
                record.accessTokenExpiresAt(), record.lastHistoryId(), record.lastSyncedAt(),
                record.syncedThreadCount(), record.syncedMessageCount()
        );
    }

    private GmailConnectionRecord mapRecord(ResultSet resultSet, int rowNum) throws SQLException {
        return new GmailConnectionRecord(
                resultSet.getString("user_id"),
                resultSet.getString("email_address"),
                resultSet.getString("access_token"),
                resultSet.getString("encrypted_refresh_token"),
                resultSet.getObject("access_token_expires_at", Instant.class),
                resultSet.getString("last_history_id"),
                resultSet.getObject("last_synced_at", Instant.class),
                resultSet.getLong("synced_thread_count"),
                resultSet.getLong("synced_message_count")
        );
    }

    public record GmailConnectionRecord(
            String userId,
            String emailAddress,
            String accessToken,
            String encryptedRefreshToken,
            Instant accessTokenExpiresAt,
            String lastHistoryId,
            Instant lastSyncedAt,
            long syncedThreadCount,
            long syncedMessageCount
    ) {
    }
}
