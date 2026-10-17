package com.pm.filmfuse.reco.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /me/taste-profile}. {@code topGenres}: at most 5 genres whose value (learned, else
 * stated) is above neutral (&gt; 5), highest first - may be fewer than 5 or empty. {@code dimensions}:
 * every genre then every mood, in the fixed vector order. {@code persona} is a cosmetic label (null
 * without a vector; "Eclectic Film Buff"-style fallback when nothing is above neutral). Mirrored by hand in
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
