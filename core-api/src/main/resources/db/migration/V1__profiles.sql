-- Profiles: one row per Supabase Auth user. id == auth.users.id (the JWT `sub`), never generated
-- here. No FK to auth.users so the schema also runs on vanilla Postgres (Testcontainers); account
-- deletion is handled by core-api (DELETE /me, later phase).

create schema if not exists app;

create table app.profiles (
    id                      uuid primary key,
    username                text,
    display_name            text check (display_name is null or char_length(display_name) <= 77),
    profile_image_url       text,
    bio                     text check (bio is null or char_length(bio) <= 300),
    status                  text not null default 'ACTIVE'
                            check (status in ('ACTIVE', 'SUSPENDED', 'PENDING_VERIFICATION')),
    onboarding_step         int not null default 0 check (onboarding_step >= 0),
    onboarding_completed_at timestamptz,
    created_at              timestamptz not null default now(),
    updated_at              timestamptz not null default now(),
    -- Null until chosen during onboarding. Lowercase only, so a plain unique index is case-insensitive.
    constraint profiles_username_format
        check (username is null or username ~ '^[a-z0-9_]{3,30}$')
);

create unique index profiles_username_key on app.profiles (username);
