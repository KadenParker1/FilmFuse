package com.pm.filmfuse.rating.dto;

import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import java.time.Instant;
import java.util.UUID;

/**
 * A post-watch prompt: a movie picked in one of the caller's sessions that they have not rated or
 * dismissed. {@code groupName} is null for an ad-hoc session (or a group deleted since);
 * {@code pickedAt} is when the session picked it. Mirrored by hand in client/types/api.ts.
 */
public record PendingRatingDTO(
        MovieSummaryDTO movie,
        UUID sessionId,
        String groupName,
        Instant pickedAt) {
}
