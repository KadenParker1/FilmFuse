-- Phase 5 post-watch prompts. GET /me/pending-ratings lists movies from the caller's watch_history
-- (last 14 days) that they have not rated and not dismissed; POST .../{movieId}/dismiss stores a
-- row here so that movie is never prompted again (whatever later watch_history rows say).
--
-- Cascades from the profile (DELETE /me). ml_reader can read it through V2's default privileges
-- (a dismissal is a weak "not worth rating" signal the owner's model may want).

create table app.pending_rating_dismissals (
    user_id    uuid not null references app.profiles (id) on delete cascade,
    movie_id   int  not null references app.movies (id),
    created_at timestamptz not null default now(),
    primary key (user_id, movie_id)
);
