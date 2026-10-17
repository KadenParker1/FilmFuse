package com.pm.filmfuse.reco.dto;

/**
 * One genre or mood dimension of the caller's taste. {@code key} is {@code "genre:28"} or
 * {@code "mood:cozy"}; {@code stated} is the caller's 1-10 preference (null = never set);
 * {@code learned} is the vector's interpretable value for this dim, rounded to 1 decimal (null = no
 * vector yet). Mirrored by hand in client/types/api.ts.
 */
public record TasteDimensionDTO(String key, String label, Integer stated, Double learned) {
}
