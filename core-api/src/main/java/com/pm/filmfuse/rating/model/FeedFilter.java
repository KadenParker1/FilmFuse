package com.pm.filmfuse.rating.model;

import com.pm.filmfuse.rating.dto.FeedScope;
import java.util.UUID;

/**
 * Which ratings a feed query lists, on top of the visibility rule: a scope, optionally narrowed to
 * one movie, one author's ratings, or a single rating. {@code includeOwnPrivate} only for the viewer's own profile.
 */
public record FeedFilter(FeedScope scope, Integer movieId, UUID authorId, UUID ratingId, boolean includeOwnPrivate) {

    public static FeedFilter feed(FeedScope scope) {
        return new FeedFilter(scope, null, null, null, false);
    }

    public static FeedFilter movie(int movieId, FeedScope scope) {
        return new FeedFilter(scope, movieId, null, null, false);
    }

    public static FeedFilter author(UUID authorId, boolean self) {
        return new FeedFilter(null, null, authorId, null, self);
    }

    /** One rating ({@code GET /ratings/{id}}): visible anywhere, so the author's own PRIVATE ones too. */
    public static FeedFilter rating(UUID ratingId) {
        return new FeedFilter(null, null, null, ratingId, true);
    }
}
