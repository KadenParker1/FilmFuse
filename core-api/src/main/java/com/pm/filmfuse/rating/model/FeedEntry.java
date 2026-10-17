package com.pm.filmfuse.rating.model;

import com.pm.filmfuse.rating.dto.RatingVisibility;
import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.social.model.SocialUser;
import java.time.Instant;
import java.util.UUID;

/** One row of a feed query: a visible rating with its author, the movie's certification and its likes and visible comment count. */
public record FeedEntry(
        UUID id,
        SocialUser author,
        int movieId,
        Certification certification,
        int score,
        String body,
        boolean containsSpoilers,
        RatingVisibility visibility,
        long likeCount,
        boolean likedByMe,
        long commentCount,
        Instant createdAt,
        Instant updatedAt) {
}
