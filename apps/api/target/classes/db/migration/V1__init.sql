create extension if not exists vector;

create table if not exists app_user (
    id text primary key,
    email_address text not null unique,
    display_name text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists gmail_connection (
    user_id text primary key references app_user(id) on delete cascade,
    email_address text not null,
    encrypted_refresh_token text not null,
    access_token text not null,
    access_token_expires_at timestamptz,
    last_history_id text,
    last_synced_at timestamptz,
    synced_thread_count bigint not null default 0,
    synced_message_count bigint not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists sync_cursor (
    user_id text primary key references app_user(id) on delete cascade,
    last_history_id text,
    last_synced_at timestamptz,
    sync_mode text not null default 'initial',
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists email_thread (
    id uuid primary key default gen_random_uuid(),
    user_id text not null references app_user(id) on delete cascade,
    thread_id text not null,
    subject text,
    last_message_at timestamptz,
    history_id text,
    label_ids_json text,
    category text,
    summary text,
    message_count integer not null default 0,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, thread_id)
);

create index if not exists idx_email_thread_user_last_message on email_thread(user_id, last_message_at desc);
create index if not exists idx_email_thread_category on email_thread(user_id, category);

create table if not exists email_message (
    id uuid primary key default gen_random_uuid(),
    user_id text not null references app_user(id) on delete cascade,
    message_id text not null,
    thread_id text not null,
    message_id_header text,
    in_reply_to text,
    references_header text,
    from_address text,
    to_addresses_json text,
    cc_addresses_json text,
    subject text,
    sent_at timestamptz,
    body_text text,
    body_html text,
    snippet text,
    summary text,
    category text,
    raw_internal_date bigint,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, message_id)
);

create index if not exists idx_email_message_thread_sent_at on email_message(user_id, thread_id, sent_at asc);
create index if not exists idx_email_message_sender on email_message(user_id, from_address);

create table if not exists email_embedding (
    id uuid primary key default gen_random_uuid(),
    user_id text not null references app_user(id) on delete cascade,
    source_type text not null,
    source_id text not null,
    thread_id text not null,
    content text not null,
    sender text,
    sent_at timestamptz,
    embedding vector(768) not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    unique (user_id, source_type, source_id)
);

create index if not exists idx_email_embedding_user_source on email_embedding(user_id, source_type, source_id);
create index if not exists idx_email_embedding_thread on email_embedding(user_id, thread_id);

create table if not exists chat_conversation (
    id text primary key,
    user_id text not null references app_user(id) on delete cascade,
    title text,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists chat_message (
    id text primary key,
    conversation_id text not null references chat_conversation(id) on delete cascade,
    role text not null,
    content text not null,
    citations_json jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now()
);

create index if not exists idx_chat_message_conversation on chat_message(conversation_id, created_at asc);

create table if not exists draft_email (
    id text primary key,
    user_id text not null references app_user(id) on delete cascade,
    gmail_thread_id text,
    mode text not null,
    subject text not null,
    body text not null,
    status text not null default 'draft',
    citations_json jsonb not null default '[]'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create table if not exists newsletter_item (
    id text primary key,
    user_id text not null references app_user(id) on delete cascade,
    canonical_title text not null,
    canonical_url text,
    source_names text[] not null default '{}',
    summary text not null,
    embedding vector(768),
    digest_date date not null,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

create index if not exists idx_newsletter_item_digest_date on newsletter_item(user_id, digest_date desc);
