package com.pm.filmfuse.rating.dto;

import com.pm.filmfuse.movie.dto.MovieSummaryDTO;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** The caller's rating of a movie. {@code watchedOn} is an ISO date. Mirrored by hand in client/types/api.ts. */
public record RatingDTO(
        UUID id,
        MovieSummaryDTO movie,
        int score,
        String body,
        boolean containsSpoilers,
        RatingVisibility visibility,
        RatingSource source,
        LocalDate watchedOn,
        Instant createdAt,
        Instant updatedAt) {
}
