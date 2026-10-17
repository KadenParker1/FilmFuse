package com.pm.filmfuse.rating.dto;

import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import com.pm.filmfuse.reco.dto.Certification;
import com.pm.filmfuse.social.dto.UserSummaryDTO;
import java.time.Instant;
import java.util.UUID;

/**
 * A rating as another user (or its author) sees it in the feed, on a movie and on a profile.
 * {@code id} is the rating id (for {@code /ratings/{id}/like}); {@code certification} is the
 * movie's, null when unknown; {@code mine} = the caller wrote it;
 * {@code commentCount} = comments the caller can see (RatingVisibilitySql#commentVisibleTo). Mirrored by hand in
 * client/types/api.ts.
 */
public record FeedItemDTO(
        UUID id,
        UserSummaryDTO author,
        MovieSummaryDTO movie,
        Certification certification,
        int score,
        String body,
        boolean containsSpoilers,
        RatingVisibility visibility,
        long likeCount,
        boolean likedByMe,
        long commentCount,
        boolean mine,
        Instant createdAt,
        Instant updatedAt) {
}
