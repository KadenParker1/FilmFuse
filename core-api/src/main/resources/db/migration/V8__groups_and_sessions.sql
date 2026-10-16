-- Phase 3: saved groups, recommendation sessions (group or ad-hoc), votes and watch history.
--
-- Deletion rules (account deletion = deleting the profile row):
--   * group_members, rec_session_members, rec_session_votes and watch_history rows of the deleted
--     user cascade away. Before that, core-api hands over ownership of any group they own
--     (GroupMembershipCleanup), so groups.owner_id's cascade is only a last-resort backstop.
--   * Sessions survive their creator (created_by set null) and their group (group_id set null), so
--     the other members keep their history. watch_history keeps its rows if a session goes.
-- Certification / dimension / level values use the V6 vocabulary; other enums are uppercase Java
-- names, as in profiles.status. ml_reader can read all of these through V2's default privileges.

create table app.groups (
    id                uuid primary key,
    name              text not null check (char_length(name) between 1 and 40),
    owner_id          uuid not null references app.profiles (id) on delete cascade,
    emoji             text check (emoji is null or char_length(emoji) <= 8),
    color             text check (color is null or color ~ '^#[0-9A-Fa-f]{6}$'),
    -- The owner's comfort cap for the whole group (e.g. a kids group at PG). Never loosens members' limits.
    max_certification text
        check (max_certification is null or max_certification in ('G', 'PG', 'PG-13', 'R', 'NC-17')),
    content_limits    jsonb not null default '{}' check (jsonb_typeof(content_limits) = 'object'),
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now()
);

create index groups_owner_idx on app.groups (owner_id);

-- INVITED rows count towards the 20-member cap; a decline or removal deletes the row.
create table app.group_members (
    group_id   uuid not null references app.groups (id) on delete cascade,
    user_id    uuid not null references app.profiles (id) on delete cascade,
    role       text not null check (role in ('OWNER', 'MEMBER')),
    status     text not null check (status in ('INVITED', 'ACTIVE')),
    invited_by uuid references app.profiles (id) on delete set null,
    invited_at timestamptz not null default now(),
    joined_at  timestamptz,
    primary key (group_id, user_id),
    constraint group_members_joined_at check ((status = 'ACTIVE') = (joined_at is not null)),
    constraint group_members_owner_is_active check (role <> 'OWNER' or status = 'ACTIVE')
);

-- At most one owner per group; "my groups" lookups.
create unique index group_members_one_owner_idx on app.group_members (group_id) where role = 'OWNER';
create index group_members_user_idx on app.group_members (user_id);

create table app.rec_sessions (
    id               uuid primary key,
    group_id         uuid references app.groups (id) on delete set null,
    created_by       uuid references app.profiles (id) on delete set null,
    -- SessionFiltersDTO as JSON: {"maxRuntime": 120, "minYear": 1990, "genreIds": [35]}.
    filters          jsonb not null default '{}' check (jsonb_typeof(filters) = 'object'),
    -- The effective comfort filter at creation / last refresh (no member attribution):
    -- {"maxCertification": "PG-13", "contentLimits": {"violence": "mild"}, "allowUnrated": false}.
    effective_filter jsonb not null default '{}' check (jsonb_typeof(effective_filter) = 'object'),
    strategy         text not null default 'MEAN'
        check (strategy in ('MEAN', 'LEAST_MISERY', 'MOST_PLEASURE', 'CLOSENESS_WEIGHTED', 'FAIRNESS')),
    -- EXPIRED is reported by the API for OPEN sessions older than 24h; it is not written today.
    status           text not null default 'OPEN' check (status in ('OPEN', 'DECIDED', 'EXPIRED')),
    picked_movie_id  int references app.movies (id),
    decided_at       timestamptz,
    created_at       timestamptz not null default now(),
    constraint rec_sessions_decided check ((status = 'DECIDED') = (picked_movie_id is not null))
);

create index rec_sessions_group_idx on app.rec_sessions (group_id, created_at desc);

-- Members are snapshotted when the session is created. has_vector: whether the member had an
-- interest vector at that moment (members without one get a popularity prior; flagged in the UI).
create table app.rec_session_members (
    session_id uuid not null references app.rec_sessions (id) on delete cascade,
    user_id    uuid not null references app.profiles (id) on delete cascade,
    has_vector boolean not null default false,
    primary key (session_id, user_id)
);

-- "My sessions", newest first, and the realtime membership check (supabase/migrations).
create index rec_session_members_user_idx on app.rec_session_members (user_id);

create table app.rec_session_results (
    session_id    uuid not null references app.rec_sessions (id) on delete cascade,
    movie_id      int not null references app.movies (id),
    rank          int not null check (rank >= 1),
    group_score   double precision not null,
    -- {"<userId>": score, ...} as returned by the ranker.
    member_scores jsonb not null default '{}' check (jsonb_typeof(member_scores) = 'object'),
    explanation   text not null default '',
    -- The movie's certification when it was recommended (V6 vocabulary; null = unknown).
    certification text
        check (certification is null or certification in ('G', 'PG', 'PG-13', 'R', 'NC-17')),
    created_at    timestamptz not null default now(),
    primary key (session_id, movie_id),
    constraint rec_session_results_rank_key unique (session_id, rank)
);

-- One vote per member per result; it disappears with the member or the result.
create table app.rec_session_votes (
    session_id uuid not null,
    user_id    uuid not null,
    movie_id   int not null,
    vote       text not null check (vote in ('UP', 'DOWN', 'VETO')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    primary key (session_id, user_id, movie_id),
    foreign key (session_id, user_id) references app.rec_session_members (session_id, user_id) on delete cascade,
    foreign key (session_id, movie_id) references app.rec_session_results (session_id, movie_id) on delete cascade
);

create index rec_session_votes_movie_idx on app.rec_session_votes (session_id, movie_id);

-- What people watched (written when a session picks a movie; later also from ratings). Read by the
-- ML service and by Phase 5 implicit closeness (sessions together).
create table app.watch_history (
    id         bigint generated always as identity primary key,
    user_id    uuid not null references app.profiles (id) on delete cascade,
    movie_id   int not null references app.movies (id),
    session_id uuid references app.rec_sessions (id) on delete set null,
    watched_at timestamptz not null default now()
);

create index watch_history_user_idx on app.watch_history (user_id, watched_at desc);
create index watch_history_session_idx on app.watch_history (session_id);
