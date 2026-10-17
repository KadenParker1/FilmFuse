-- Phase 1b: stated taste (genre / mood prefs, favourites), ratings, calibration "haven't seen",
-- "not interested" dismissals and the interest-vector pipeline.
--
-- Every score is an integer 1-10. Every row belongs to a profile and cascades away with it
-- (account deletion). Movie references cascade too (movies are a cache and are never deleted today).
-- ml_reader can read all of these through V2's default privileges (the ML service reads signals
-- directly for training / candidate retrieval; core-api owns every write).
--
-- Vocabulary: ratings.source uses the ML contract's lowercase values ('onboarding_swipe' ...) because
-- the ML service reads it directly, like V6's certification / levels. Everything only core-api reads
-- uses the uppercase Java enum names, as in V8 (ratings.visibility, vector reasons).
-- Mood tags are python-ml-service's MOOD_TAGS (app/services/recommender.py), in that order.

create table app.user_genre_prefs (
    user_id    uuid not null references app.profiles (id) on delete cascade,
    genre_id   int not null references app.genres (id) on delete cascade,
    score      smallint not null check (score between 1 and 10),
    updated_at timestamptz not null default now(),
    primary key (user_id, genre_id)
);

create table app.user_mood_prefs (
    user_id    uuid not null references app.profiles (id) on delete cascade,
    mood_tag   text not null check (mood_tag in ('mind-bending', 'cozy', 'laugh-out-loud', 'edge-of-seat',
                                                 'tearjerker', 'visually-stunning', 'dark-gritty', 'feel-good')),
    score      smallint not null check (score between 1 and 10),
    updated_at timestamptz not null default now(),
    primary key (user_id, mood_tag)
);

-- All-time favourites (onboarding step 3, at most 10 - enforced by core-api). rank 1 = top.
create table app.user_favorite_movies (
    user_id    uuid not null references app.profiles (id) on delete cascade,
    movie_id   int not null references app.movies (id) on delete cascade,
    rank       int not null check (rank >= 1),
    created_at timestamptz not null default now(),
    primary key (user_id, movie_id),
    constraint user_favorite_movies_rank_key unique (user_id, rank)
);

-- The single source of truth for every movie score. A "review" is a rating with visibility <> PRIVATE
-- (body optional). Calibration swipes are always PRIVATE. Re-rating overwrites the row.
create table app.ratings (
    id                uuid primary key,
    user_id           uuid not null references app.profiles (id) on delete cascade,
    movie_id          int not null references app.movies (id) on delete cascade,
    score             smallint not null check (score between 1 and 10),
    source            text not null check (source in ('onboarding_swipe', 'quick_rate', 'post_watch')),
    body              text check (body is null or char_length(body) <= 1000),
    contains_spoilers boolean not null default false,
    visibility        text not null default 'FRIENDS' check (visibility in ('PRIVATE', 'FRIENDS', 'PUBLIC')),
    watched_on        date,
    created_at        timestamptz not null default now(),
    updated_at        timestamptz not null default now(),
    constraint ratings_user_movie_key unique (user_id, movie_id)
);

-- "My ratings" newest first, and the upcoming feed (a user's / everyone's public posts, newest first).
create index ratings_user_created_idx on app.ratings (user_id, created_at desc, id desc);
create index ratings_visibility_created_idx on app.ratings (visibility, created_at desc, id desc);
create index ratings_movie_idx on app.ratings (movie_id);

-- "Haven't seen" from calibration: never shown again for calibration, excluded from "For you".
create table app.rating_unseen (
    user_id    uuid not null references app.profiles (id) on delete cascade,
    movie_id   int not null references app.movies (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, movie_id)
);

-- "Not interested" on a For-you pick: a weak negative signal, and never recommended again.
create table app.rec_dismissals (
    user_id    uuid not null references app.profiles (id) on delete cascade,
    movie_id   int not null references app.movies (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (user_id, movie_id)
);

-- The current interest vector per user (hybrid: interpretable genre+mood dims on the 1-10 scale in
-- python-ml-service's GENRE_IDS then MOOD_TAGS order, plus an optional learned latent part; real[]
-- for now - pgvector once the owner sizes the latent dimension).
--
-- A row can exist before any vector does: the first taste write creates it dirty with version 0
-- and a null vector. version > 0 <=> a vector has been built.
--
-- dirty / dirty_reason / dirty_since: set by every taste write in the same transaction (latest reason
-- wins; dirty_since = the latest mark, strictly increasing, so the scheduler's debounce waits for a
-- quiet period). VectorRefreshScheduler rebuilds rows dirty for at least the debounce, and clears the
-- flag only if dirty_since is unchanged since it read the signals (a newer mark keeps the row dirty).
create table app.user_interest_vectors (
    user_id       uuid primary key references app.profiles (id) on delete cascade,
    interpretable real[],
    latent        real[],
    dim           int check (dim is null or dim >= 0),
    model_version text,
    version       int not null default 0 check (version >= 0),
    dirty         boolean not null default false,
    dirty_reason  text check (dirty_reason in ('ONBOARDING', 'TASTE_EDIT', 'RATING', 'DISMISS', 'PICK')),
    dirty_since   timestamptz,
    updated_at    timestamptz,
    constraint user_interest_vectors_built check ((version = 0) = (interpretable is null)),
    constraint user_interest_vectors_dirty check (dirty = (dirty_since is not null) and dirty = (dirty_reason is not null))
);

create index user_interest_vectors_dirty_idx on app.user_interest_vectors (dirty_since) where dirty;

-- Every built version, for the "how your taste changed" view and for analysing the algorithm.
-- reason = the latest taste signal before the build.
create table app.user_interest_vector_history (
    user_id       uuid not null references app.profiles (id) on delete cascade,
    version       int not null check (version >= 1),
    interpretable real[] not null,
    latent        real[],
    dim           int not null check (dim >= 0),
    model_version text not null,
    reason        text not null check (reason in ('ONBOARDING', 'TASTE_EDIT', 'RATING', 'DISMISS', 'PICK')),
    created_at    timestamptz not null default now(),
    primary key (user_id, version)
);
