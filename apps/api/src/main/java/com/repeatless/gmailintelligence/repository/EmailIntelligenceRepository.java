package com.repeatless.gmailintelligence.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.repeatless.gmailintelligence.dto.ApiDtos.SourceCitation;
import com.repeatless.gmailintelligence.model.GmailModels.GmailMessageSnapshot;
import com.repeatless.gmailintelligence.model.GmailModels.GmailThreadSnapshot;

@Repository
public class EmailIntelligenceRepository {

    private final JdbcTemplate jdbcTemplate;

    public EmailIntelligenceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsertThread(String userId, GmailThreadSnapshot threadSnapshot, String summary, String category) {
        jdbcTemplate.update(
                """
                        insert into email_thread(
                            user_id, thread_id, subject, last_message_at, history_id,
                            label_ids_json, category, summary, message_count, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
                        on conflict (user_id, thread_id) do update set
                            subject = excluded.subject,
                            last_message_at = excluded.last_message_at,
                            history_id = excluded.history_id,
                            label_ids_json = excluded.label_ids_json,
                            category = excluded.category,
                            summary = excluded.summary,
                            message_count = excluded.message_count,
                            updated_at = now()
                        """,
                userId, threadSnapshot.threadId(), threadSnapshot.subject(),
                threadSnapshot.updatedAt(), threadSnapshot.historyId(), threadSnapshot.labelIds(),
                category, summary, threadSnapshot.messages().size()
        );
    }

    public void upsertMessage(String userId, GmailMessageSnapshot message, String category, String summary) {
        jdbcTemplate.update(
                """
                        insert into email_message(
                            user_id, message_id, thread_id, message_id_header, in_reply_to,
                            references_header, from_address, to_addresses_json, cc_addresses_json, subject,
                            sent_at, body_text, body_html, category, summary, created_at, updated_at
                        ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now(), now())
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
                            category = excluded.category,
                            summary = excluded.summary,
                            updated_at = now()
                        """,
                userId, message.messageId(), message.threadId(), message.messageIdHeader(),
                message.inReplyTo(), message.references(), message.fromAddress(), message.toAddresses(),
                message.ccAddresses(), message.subject(), message.sentAt(), message.bodyText(), message.bodyHtml(),
                category, summary
        );
    }

    public List<GmailThreadSnapshot> findRecentThreads(String userId, int limit) {
        return jdbcTemplate.query(
                """
                        select thread_id, history_id, subject, label_ids_json, last_message_at
                        from email_thread
                        where user_id = ?
                        order by coalesce(last_message_at, updated_at) desc
                        limit ?
                        """,
                (resultSet, rowNum) -> new GmailThreadSnapshot(
                        resultSet.getString("thread_id"),
                        resultSet.getString("history_id"),
                        resultSet.getString("subject"),
                        resultSet.getString("label_ids_json"),
                        resultSet.getObject("last_message_at", Instant.class),
                        List.of()
                ),
                userId, limit
        );
    }

    public Optional<ThreadTranscript> findThreadTranscript(String userId, String threadId) {
        List<ThreadTranscriptMessage> messages = jdbcTemplate.query(
                """
                        select message_id, thread_id, message_id_header, in_reply_to, references_header,
                               from_address, to_addresses_json, cc_addresses_json, subject, sent_at,
                               body_text, body_html, summary
                        from email_message
                        where user_id = ? and thread_id = ?
                        order by sent_at asc
                        """,
                this::mapTranscriptMessage,
                userId, threadId
        );

        if (messages.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(new ThreadTranscript(threadId, messages));
    }

    public List<SourceCitation> findRecentSourceCitations(String userId, int limit) {
        return jdbcTemplate.query(
                """
                        select message_id, from_address, sent_at, coalesce(summary, substring(body_text from 1 for 180)) as snippet
                        from email_message
                        where user_id = ?
                        order by sent_at desc
                        limit ?
                        """,
                (resultSet, rowNum) -> new SourceCitation(
                        "message",
                        resultSet.getString("message_id"),
                        resultSet.getString("from_address"),
                        resultSet.getObject("sent_at", Instant.class),
                        resultSet.getString("snippet")
                ),
                userId, limit
        );
    }

    private ThreadTranscriptMessage mapTranscriptMessage(ResultSet resultSet, int rowNum) throws SQLException {
        return new ThreadTranscriptMessage(
                resultSet.getString("message_id"),
                resultSet.getString("thread_id"),
                resultSet.getString("message_id_header"),
                resultSet.getString("in_reply_to"),
                resultSet.getString("references_header"),
                resultSet.getString("from_address"),
                resultSet.getString("to_addresses_json"),
                resultSet.getString("cc_addresses_json"),
                resultSet.getString("subject"),
                resultSet.getObject("sent_at", Instant.class),
                resultSet.getString("body_text"),
                resultSet.getString("body_html"),
                resultSet.getString("summary")
        );
    }

    public record ThreadTranscript(String threadId, List<ThreadTranscriptMessage> messages) {
        public String toTranscriptText() {
            StringBuilder transcript = new StringBuilder();
            for (ThreadTranscriptMessage message : messages) {
                transcript.append("From: ").append(message.fromAddress()).append('\n');
                transcript.append("Sent: ").append(message.sentAt()).append('\n');
                transcript.append("Subject: ").append(message.subject()).append('\n');
                transcript.append("Body: ").append(message.bodyText()).append('\n');
                transcript.append("---\n");
            }
            return transcript.toString();
        }
    }

    public record ThreadTranscriptMessage(
            String messageId,
            String threadId,
            String messageIdHeader,
            String inReplyTo,
            String referencesHeader,
            String fromAddress,
            String toAddressesJson,
            String ccAddressesJson,
            String subject,
            Instant sentAt,
            String bodyText,
            String bodyHtml,
            String summary
    ) {
    }
}
