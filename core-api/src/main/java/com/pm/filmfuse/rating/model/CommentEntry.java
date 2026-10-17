package com.pm.filmfuse.rating.model;

import com.pm.filmfuse.social.model.SocialUser;
import java.time.Instant;
import java.util.UUID;

/** One {@code rating_comments} row with its author and the rating's author (for {@code canDelete}). */
public record CommentEntry(UUID id, UUID ratingId, SocialUser author, UUID ratingAuthorId, String body,
                           Instant createdAt) {
}
