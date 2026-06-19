alter table draft_email
    add column if not exists to_address text,
    add column if not exists in_reply_to text,
    add column if not exists references_header text,
    add column if not exists gmail_message_id text;
