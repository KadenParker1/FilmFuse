-- Tombstones for deleted accounts. A Supabase access token stays valid until it expires even after
-- DELETE /me removes the Auth user, so core-api refuses any token whose `sub` is listed here
-- (401 account_deleted) and never re-provisions a profile for it. Only the id - no PII.

create table app.deleted_accounts (
    id         uuid primary key,
    deleted_at timestamptz not null default now()
);
