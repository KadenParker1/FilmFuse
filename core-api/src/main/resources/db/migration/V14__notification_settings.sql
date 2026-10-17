-- Phase 5 email notification preferences (GET/PUT /me/settings/notifications).
--
-- Its own table rather than more columns on app.user_settings: that table holds the comfort
-- filter (read on every recommendation), these are only read when an email is about to go out,
-- and the weekly digest job selects its recipients through a partial index here.
-- A user without a row has the defaults: everything on except weekly_digest.
-- Cascades from the profile (DELETE /me). ml_reader can read it through V2's default privileges.

create table app.notification_settings (
    user_id         uuid primary key references app.profiles (id) on delete cascade,
    friend_requests boolean not null default true,
    group_invites   boolean not null default true,
    session_started boolean not null default true,
    comments        boolean not null default true,
    weekly_digest   boolean not null default false,
    updated_at      timestamptz not null default now()
);

-- DigestJob's recipient list.
create index notification_settings_digest_idx on app.notification_settings (user_id) where weekly_digest;
