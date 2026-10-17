package com.pm.filmfuse.rating.dto;

/**
 * One calibration swipe in {@code POST /me/ratings/batch}: exactly one of {@code score} (1-10) or
 * {@code unseen: true} ("Haven't seen"). Boxed because Jackson 3 refuses a missing primitive;
 * validated by RatingService. Mirrored by hand in client/types/api.ts.
 */
public record CalibrationRatingDTO(Integer movieId, Integer score, Boolean unseen) {
}
