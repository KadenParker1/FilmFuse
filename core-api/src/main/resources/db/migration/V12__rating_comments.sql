-- Phase 5 comments on ratings. Who may read or write them is decided in core-api by
-- rating.repository.RatingVisibilitySql (the rating must be visible to the caller; comments by
-- anyone blocked either way, or suspended, are hidden).
--
-- A comment goes with its rating (ON DELETE CASCADE) and with its author's profile, so DELETE /me
-- removes the user's comments and, through their ratings, the comments on their ratings.
-- Re-rating keeps ratings.id, so comments survive it. ml_reader can read it through V2.

create table app.rating_comments (
    id         uuid primary key,
    rating_id  uuid not null references app.ratings (id) on delete cascade,
    user_id    uuid not null references app.profiles (id) on delete cascade,
    body       text not null check (char_length(body) between 1 and 500),
    created_at timestamptz not null default now()
);

-- A rating's comments, oldest first (the list's keyset) - also serves commentCount.
create index rating_comments_rating_idx on app.rating_comments (rating_id, created_at, id);
-- The cascade when an author's profile is deleted.
create index rating_comments_user_idx on app.rating_comments (user_id);
