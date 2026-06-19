-- Ensure access_token column exists (may have been created as encrypted_access_token in older schema)
alter table gmail_connection
    add column if not exists access_token text;

-- If the old column name existed, copy data across and drop it
do $$
begin
    if exists (
        select 1 from information_schema.columns
        where table_name = 'gmail_connection' and column_name = 'encrypted_access_token'
    ) then
        update gmail_connection
        set access_token = encrypted_access_token
        where access_token is null and encrypted_access_token is not null;

        alter table gmail_connection drop column if exists encrypted_access_token;
    end if;
end$$;

-- Set default empty string for any nulls so NOT NULL can be applied
update gmail_connection set access_token = '' where access_token is null;

-- Make it not null now that data is migrated
alter table gmail_connection alter column access_token set not null;
