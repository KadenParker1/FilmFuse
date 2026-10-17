package com.pm.filmfuse.rating.model;

import com.pm.filmfuse.rating.dto.FeedScope;
import java.util.UUID;

/**
 * Which ratings a feed query lists, on top of the visibility rule: a scope, optionally narrowed to
 * one movie, or one author's ratings. {@code includeOwnPrivate} only for the viewer's own profile.
 */
public record FeedFilter(FeedScope scope, Integer movieId, UUID authorId, boolean includeOwnPrivate) {

    public static FeedFilter feed(FeedScope scope) {
        return new FeedFilter(scope, null, null, false);
    }

    public static FeedFilter movie(int movieId, FeedScope scope) {
        return new FeedFilter(scope, movieId, null, false);
    }

    public static FeedFilter author(UUID authorId, boolean self) {
        return new FeedFilter(null, null, authorId, self);
    }
}
