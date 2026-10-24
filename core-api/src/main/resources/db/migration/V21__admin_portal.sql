-- Admin portal (com.pm.filmfuse.admin): an admin role, runtime settings, the moderation outcome of
-- reports and an audit log of every admin write.

-- The role. Granted ONLY by hand in SQL (see CLAUDE.md, "Admin portal"): no API writes it - the JPA
-- mapping is read-only (insertable = false, updatable = false), so a profile save can never change it.
-- SupabaseJwtAuthenticationConverter reads it on every request (the same primary-key lookup that
-- enforces suspensions), so granting or revoking applies to the next request.
alter table app.profiles add column role text not null default 'user'
    constraint profiles_role_check check (role in ('user', 'admin'));
-- V16 gave ml_reader a column-level grant on profiles; role is not in it, so ml_reader cannot read it.

-- Runtime settings an admin may change without a redeploy. Only keys in AdminSettingKey (a typed
-- registry with bounds) are ever written; a missing row means "use the configured default".
create table app.admin_settings (
    key        text primary key check (char_length(key) between 1 and 100),
    value      jsonb not null,
    updated_by uuid references app.profiles (id) on delete set null,
    updated_at timestamptz not null default now()
);

-- Every admin write: settings, job actions, report resolutions. Append-only from the application's
-- point of view. admin_id survives the admin's account deletion as null (the row is evidence).
create table app.admin_audit_log (
    id         uuid primary key,
    admin_id   uuid references app.profiles (id) on delete set null,
    action     text not null check (char_length(action) between 1 and 64),
    target     text check (target is null or char_length(target) <= 200),
    old_value  jsonb,
    new_value  jsonb,
    created_at timestamptz not null default now()
);

-- The audit list, newest first (keyset on created_at desc, id desc).
create index admin_audit_log_created_idx on app.admin_audit_log (created_at desc, id desc);

-- Moderation outcome of a report. V13 had no admin UI; the queue now resolves or dismisses with a note.
alter table app.reports
    add column resolved_at     timestamptz,
    add column resolved_by     uuid references app.profiles (id) on delete set null,
    add column resolution_note text check (resolution_note is null or char_length(resolution_note) <= 500);
alter table app.reports add constraint reports_resolution_check
    check ((status = 'OPEN') = (resolved_at is null));

-- The queue: open reports, oldest first.
create index reports_open_created_idx on app.reports (created_at, id) where status = 'OPEN';

-- Least privilege (as in V16 / V18 / V19): V2's default privileges grant ml_reader SELECT on every new
-- table; admin settings and the audit log are no recommendation signal.
revoke select on app.admin_settings from ml_reader;
revoke select on app.admin_audit_log from ml_reader;
