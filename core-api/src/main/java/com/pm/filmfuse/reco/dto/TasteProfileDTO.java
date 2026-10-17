package com.pm.filmfuse.reco.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /me/taste-profile}. {@code topGenres}: at most 5 genres by learned value (stated when
 * there is no vector), highest first. {@code dimensions}: every genre then every mood, in the fixed
 * vector order. {@code persona} is a cosmetic label (null without a vector). Mirrored by hand in
 * client/types/api.ts.
 */
public record TasteProfileDTO(
        VectorStatus status,
        Instant updatedAt,
        String modelVersion,
        String persona,
        List<TasteDimensionDTO> topGenres,
        List<TasteDimensionDTO> dimensions,
        long ratingsCount) {
}
