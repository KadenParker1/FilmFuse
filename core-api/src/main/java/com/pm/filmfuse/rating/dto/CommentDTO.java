package com.pm.filmfuse.rating.dto;

import com.pm.filmfuse.social.dto.UserSummaryDTO;
import java.time.Instant;
import java.util.UUID;

/**
 * A comment on a rating. {@code mine} = the caller wrote it; {@code canDelete} = the caller wrote
 * it or wrote the rating. Mirrored by hand in client/types/api.ts.
 */
public record CommentDTO(
        UUID id,
        UUID ratingId,
        UserSummaryDTO author,
        String body,
        Instant createdAt,
        boolean mine,
        boolean canDelete) {
}
