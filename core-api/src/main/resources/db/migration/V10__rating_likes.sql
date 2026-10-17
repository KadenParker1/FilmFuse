-- Phase 2b ratings feed: likes on ratings, plus indexes for the feed's keyset queries.
--
-- A like hangs off the rating and the liker, both ON DELETE CASCADE: deleting a rating deletes its
-- likes, and DELETE /me removes the user's likes (and, through ratings, the likes on their ratings).
-- Re-rating is an upsert that keeps ratings.id, so likes survive it.
-- ml_reader can read the new table through the default privileges granted in V2.

create table app.rating_likes (
    rating_id  uuid not null references app.ratings (id) on delete cascade,
    user_id    uuid not null references app.profiles (id) on delete cascade,
    created_at timestamptz not null default now(),
    primary key (rating_id, user_id)
);

-- The primary key leads with rating_id, so it is the "likes of a rating" index (likeCount /
-- likedByMe). This one serves the cascade when a liker's profile is deleted.
create index rating_likes_user_idx on app.rating_likes (user_id);

-- Feed keysets are (updated_at desc, id desc): a re-rated review moves back to the top.
-- FRIENDS scope and /users/{id}/ratings: one index range per author.
create index ratings_user_updated_idx on app.ratings (user_id, updated_at desc, id desc);
-- EVERYONE scope: public, non-swipe ratings newest first.
create index ratings_public_updated_idx on app.ratings (updated_at desc, id desc)
    where visibility = 'PUBLIC' and source <> 'onboarding_swipe';
-- /movies/{id}/ratings. Supersedes ratings_movie_idx (its prefix).
create index ratings_movie_updated_idx on app.ratings (movie_id, updated_at desc, id desc);
drop index app.ratings_movie_idx;
-- V9 added this for the feed before the feed was keyed on updated_at; nothing reads it.
drop index app.ratings_visibility_created_idx;
