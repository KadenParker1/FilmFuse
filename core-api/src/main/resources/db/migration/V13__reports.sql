-- Phase 5 reporting (app-store UGC rules): a user reports a rating, a comment or another user.
-- No admin UI yet; reports are reviewed straight from the table.
--
-- target_id has no foreign key on purpose: a report outlives the content it is about (a deleted
-- rating or account must not erase the evidence that it was reported). It only holds an id.
-- The reporter's rows cascade with their profile (DELETE /me). One OPEN report per reporter and
-- target (repeats are idempotent); a RESOLVED / DISMISSED one does not block a new report.
-- ml_reader can read it through V2's default privileges.

create table app.reports (
    id          uuid primary key,
    reporter_id uuid not null references app.profiles (id) on delete cascade,
    target_type text not null check (target_type in ('RATING', 'COMMENT', 'USER')),
    target_id   uuid not null,
    reason      text not null
        check (reason in ('SPAM', 'HARASSMENT', 'HATE', 'SEXUAL_CONTENT', 'SPOILERS', 'OTHER')),
    details     text check (details is null or char_length(details) <= 500),
    status      text not null default 'OPEN' check (status in ('OPEN', 'RESOLVED', 'DISMISSED')),
    created_at  timestamptz not null default now()
);

create unique index reports_one_open_idx on app.reports (reporter_id, target_type, target_id)
    where status = 'OPEN';
-- Moderation queue: everything said about one target.
create index reports_target_idx on app.reports (target_type, target_id);
