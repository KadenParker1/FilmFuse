package com.pm.filmfuse.reco.dto;

import java.time.Instant;
import java.util.List;

/**
 * One built vector version. {@code topGenres}: at most 3 genres above neutral (&gt; 5) by that
 * version's interpretable values, highest first - may be fewer or empty ({@code stated} is always
 * null here). Mirrored by hand in client/types/api.ts.
 */
public record TasteHistoryEntryDTO(
        int version,
        VectorChangeReason reason,
        String modelVersion,
        Instant createdAt,
        List<TasteDimensionDTO> topGenres) {
}
