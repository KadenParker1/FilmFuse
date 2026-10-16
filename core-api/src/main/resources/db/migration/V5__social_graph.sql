-- Phase 2 social graph: friendships, directional closeness, blocks and invite links, plus the
-- caller's email on profiles (for social notification emails).
--
-- Every row hangs off app.profiles with ON DELETE CASCADE, so DELETE /me removes a user's whole
-- social footprint. All access goes through com.pm.filmfuse.social.service.SocialGraphService.
-- ml_reader can read the new tables through the default privileges granted in V2.

-- Mirrored by core-api from the JWT `email` claim on every authenticated request. Never exposed
-- through the API; only used as the recipient of social emails. Null until the user is next seen.
alter table app.profiles add column email text;

-- Username / display-name prefix search (GET /users/search). Usernames are lowercase already;
-- display names are matched lowercased. text_pattern_ops so `like 'abc%'` can use the index under
-- any database collation.
create index profiles_username_prefix_idx on app.profiles (username text_pattern_ops) where username is not null;
create index profiles_display_name_prefix_idx on app.profiles (lower(display_name) text_pattern_ops)
    where username is not null;

-- One row per unordered pair: (user_low, user_high) is the pair in uuid order, so a pair can never
-- have two rows. PENDING = a request from requested_by to the other user; ACCEPTED = friends.
create table app.friendships (
    user_low     uuid not null references app.profiles (id) on delete cascade,
    user_high    uuid not null references app.profiles (id) on delete cascade,
    status       text not null check (status in ('PENDING', 'ACCEPTED')),
    requested_by uuid not null,
    created_at   timestamptz not null default now(),
    accepted_at  timestamptz,
    primary key (user_low, user_high),
    constraint friendships_canonical_pair check (user_low < user_high),
    constraint friendships_requested_by_in_pair check (requested_by in (user_low, user_high)),
    constraint friendships_accepted_at check ((status = 'ACCEPTED') = (accepted_at is not null))
);

-- The primary key serves lookups by user_low; this one serves "all friendships of X" by user_high.
create index friendships_user_high_idx on app.friendships (user_high);

-- Directional: how close user_id feels to friend_id. One row each way per accepted friendship.
-- `score` is the implicit closeness (0.5 until the Phase 5 job computes one). The effective
-- closeness reported by the API is greatest(score, 0.8) while explicit_close is set, else score -
-- so clearing the close-friend flag restores the implicit score.
create table app.friend_closeness (
    user_id        uuid not null references app.profiles (id) on delete cascade,
    friend_id      uuid not null references app.profiles (id) on delete cascade,
    explicit_close boolean not null default false,
    score          real not null default 0.5 check (score >= 0 and score <= 1),
    updated_at     timestamptz not null default now(),
    primary key (user_id, friend_id),
    constraint friend_closeness_not_self check (user_id <> friend_id)
);

create index friend_closeness_friend_idx on app.friend_closeness (friend_id);

create table app.blocks (
    blocker_id uuid not null references app.profiles (id) on delete cascade,
    blocked_id uuid not null references app.profiles (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (blocker_id, blocked_id),
    constraint blocks_not_self check (blocker_id <> blocked_id)
);

-- "Who has blocked X" - checked on every search, profile view and friend request.
create index blocks_blocked_idx on app.blocks (blocked_id);

-- Shareable invite links. Only the SHA-256 of the token is stored; the token itself exists only
-- in the link. Reusable by any number of people until expires_at.
create table app.invites (
    id         uuid primary key,
    token_hash bytea not null unique check (octet_length(token_hash) = 32),
    inviter_id uuid not null references app.profiles (id) on delete cascade,
    created_at timestamptz not null default now(),
    expires_at timestamptz not null,
    constraint invites_expiry_after_creation check (expires_at > created_at)
);

create index invites_inviter_idx on app.invites (inviter_id);
