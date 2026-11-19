-- Least favourite movies (optional, 0-5 - enforced by core-api; rank 1 = most disliked). Shaped like
-- user_favorite_movies (V9). A movie is never in both lists (enforced by core-api under the user lock).
-- Cascades from the profile (DELETE /me) and from the movie.
create table app.user_least_favorite_movies (
    user_id    uuid not null references app.profiles (id) on delete cascade,
    movie_id   int not null references app.movies (id) on delete cascade,
    rank       int not null check (rank >= 1),
    created_at timestamptz not null default now(),
    primary key (user_id, movie_id),
    constraint user_least_favorite_movies_rank_key unique (user_id, rank)
);

-- A taste signal like favourites: ml_reader reads it (V2's default privileges already grant this;
-- stated explicitly so the intent survives a future change to those defaults).
grant select on app.user_least_favorite_movies to ml_reader;
