package com.pm.filmfuse.rating.model;

import com.pm.filmfuse.rating.dto.RatingSource;
import com.pm.filmfuse.rating.dto.RatingVisibility;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A {@code ratings} row. */
public record Rating(
        UUID id,
        UUID userId,
        int movieId,
        int score,
        String body,
        boolean containsSpoilers,
        RatingVisibility visibility,
        RatingSource source,
        LocalDate watchedOn,
        Instant createdAt,
        Instant updatedAt) {
}
