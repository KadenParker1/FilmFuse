package com.pm.filmfuse.rating.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * {@code PUT /ratings/{movieId}}: a full replacement of the caller's rating. Defaults: no body, no
 * spoilers, {@code FRIENDS}, no watch date, {@code QUICK_RATE}. {@code ONBOARDING_SWIPE} is refused
 * (calibration goes through {@code POST /me/ratings/batch}). Mirrored by hand in client/types/api.ts.
 */
public record UpsertRatingDTO(
        @NotNull(message = "Score is required")
        @Min(value = 1, message = "Must be between 1 and 10")
        @Max(value = 10, message = "Must be between 1 and 10")
        Integer score,

        @Size(max = 1000, message = "A note cannot exceed 1000 characters")
        String body,

        Boolean containsSpoilers,
        RatingVisibility visibility,
        LocalDate watchedOn,
        RatingSource source) {
}
